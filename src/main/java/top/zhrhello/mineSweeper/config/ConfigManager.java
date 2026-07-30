package top.zhrhello.mineSweeper.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import top.zhrhello.mineSweeper.logic.LogicException;
import top.zhrhello.mineSweeper.logic.Persistence;
import top.zhrhello.mineSweeper.logic.lgs.LgsCompiler;
import top.zhrhello.mineSweeper.logic.lgs.LgsScript;

import java.io.File;
import java.util.*;

/**
 * 配置管理器：负责加载 / 热重载 config.yml 与 scripts/*.lgs 脚本文件。
 *
 * <h3>配置来源</h3>
 * <ul>
 *   <li><b>config.yml</b>：游戏设置（{@code game}）、奖励流程（{@code rewards}）、
 *       引擎参数与常量（{@code logic}）</li>
 *   <li><b>scripts/*.lgs</b>：LogicStep 脚本文件，定义步骤与模块（奖励计算逻辑）</li>
 * </ul>
 *
 * <h3>热重载流程</h3>
 * <ol>
 *   <li>读取 config.yml → 解析 game / rewards / logic</li>
 *   <li>读取 scripts/ 目录下所有 .lgs 文件 → 编译为 {@link LgsScript}</li>
 *   <li>解析成功 → 原子替换内存配置</li>
 *   <li>解析失败 → 保持旧配置，返回详细错误</li>
 * </ol>
 */
public class ConfigManager {

    private static final Set<String> KNOWN_ACTIONS = new HashSet<>(Arrays.asList(
            "console_command", "player_command", "message", "broadcast", "sound",
            "give_item", "give_chest", "delay"));

    private final JavaPlugin plugin;
    private final Persistence persistence;

    // 当前生效配置（原子替换）
    private volatile Map<String, Object> constants = new LinkedHashMap<>();
    private volatile LgsScript lgsScript = new LgsScript();
    private volatile Map<String, List<RewardFlow>> rewards = new LinkedHashMap<>();
    private volatile int maxSteps = 50000;
    private volatile int maxEvalMs = 50;
    private volatile boolean autoFlagEnabled = false;
    private volatile int timeoutSeconds = 60;

    public ConfigManager(JavaPlugin plugin, Persistence persistence) {
        this.plugin = plugin;
        this.persistence = persistence;
    }

    // ===================== 热重载 =====================

    public static class ReloadResult {
        public final boolean success;
        public final List<String> errors;
        public final List<String> warnings;
        public final int stepCount;       // LogicStep 步骤数
        public final int moduleCount;     // LogicStep 模块数
        public final int actionCount;     // 奖励动作数
        public final int persistenceCount;

        ReloadResult(boolean success, List<String> errors, List<String> warnings,
                     int stepCount, int moduleCount, int actionCount, int persistenceCount) {
            this.success = success;
            this.errors = errors;
            this.warnings = warnings;
            this.stepCount = stepCount;
            this.moduleCount = moduleCount;
            this.actionCount = actionCount;
            this.persistenceCount = persistenceCount;
        }

        static ReloadResult fail(List<String> errors, List<String> warnings) {
            return new ReloadResult(false, errors, warnings, 0, 0, 0, 0);
        }

        static ReloadResult ok(int sc, int mc, int ac, int pc, List<String> warnings) {
            return new ReloadResult(true, new ArrayList<>(), warnings, sc, mc, ac, pc);
        }
    }

    public ReloadResult reload() {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.exists()) {
            writeDefaultConfig(file);
        }
        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.load(file);
        } catch (Exception e) {
            return ReloadResult.fail(
                    Collections.singletonList("无法读取 config.yml: " + e.getMessage()),
                    new ArrayList<>());
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 解析 logic 参数与常量
        Map<String, Object> constantsParsed = new LinkedHashMap<>();
        ConfigurationSection constSec = cfg.getConfigurationSection("logic.constants");
        if (constSec != null) {
            constantsParsed.putAll(constSec.getValues(false));
        }
        int maxStepsParsed = cfg.getInt("logic.max_steps", 50000);
        if (maxStepsParsed <= 0) maxStepsParsed = 50000;
        int maxEvalMsParsed = cfg.getInt("logic.max_eval_ms", 50);
        if (maxEvalMsParsed <= 0) maxEvalMsParsed = 50;

        // 解析 rewards 流程
        Map<String, List<RewardFlow>> rewardsParsed = parseRewards(cfg, errors, warnings);

        // 编译 LogicStep 脚本
        LgsScript scriptParsed = new LgsScript();
        File scriptsDir = new File(plugin.getDataFolder(), "scripts");
        if (!scriptsDir.exists()) {
            // 首次运行：释放内置示例脚本
            releaseBuiltinScripts(scriptsDir);
        }
        try {
            scriptParsed = LgsCompiler.compileDirectory(scriptsDir);
        } catch (LogicException e) {
            errors.add(e.getMessage());
        }

        // 游戏设置
        boolean autoFlag = cfg.getBoolean("game.auto_flag_enabled", false);
        int timeout = cfg.getInt("game.timeout_seconds", 60);
        if (timeout <= 0) timeout = 60;

        // 兼容性提醒：如果旧版 config.yml 仍含 logic.functions，提示用户迁移
        if (cfg.contains("logic.functions")) {
            warnings.add("config.yml 中仍存在 logic.functions（已弃用）。" +
                    "请将逻辑迁移到 scripts/*.lgs 脚本文件中。详见 DESIGN_LGS.md。");
        }

        if (!errors.isEmpty()) {
            return ReloadResult.fail(errors, warnings);
        }

        // 解析成功 → 原子替换
        synchronized (this) {
            this.constants = constantsParsed;
            this.lgsScript = scriptParsed;
            this.rewards = rewardsParsed;
            this.maxSteps = maxStepsParsed;
            this.maxEvalMs = maxEvalMsParsed;
            this.autoFlagEnabled = autoFlag;
            this.timeoutSeconds = timeout;
        }
        int actionCount = 0;
        for (List<RewardFlow> flows : rewardsParsed.values()) {
            for (RewardFlow f : flows) actionCount += f.actions.size();
        }
        return ReloadResult.ok(scriptParsed.stepCount(), scriptParsed.moduleCount(),
                actionCount, persistence.size(), warnings);
    }

    // ===================== 解析：rewards =====================

    @SuppressWarnings("unchecked")
    private Map<String, List<RewardFlow>> parseRewards(YamlConfiguration cfg, List<String> errors, List<String> warnings) {
        Map<String, List<RewardFlow>> out = new LinkedHashMap<>();
        ConfigurationSection rs = cfg.getConfigurationSection("rewards");
        if (rs == null) return out;
        for (String key : rs.getKeys(false)) {
            List<?> rawFlows = rs.getList(key);
            List<RewardFlow> flows = new ArrayList<>();
            if (rawFlows != null) {
                int fi = 0;
                for (Object fo : rawFlows) {
                    fi++;
                    if (!(fo instanceof Map)) {
                        errors.add("rewards." + key + " 流程#" + fi + " 格式错误");
                        continue;
                    }
                    Map<String, Object> fm = (Map<String, Object>) fo;
                    String trigger = fm.get("trigger") == null ? null : String.valueOf(fm.get("trigger"));
                    Map<String, String> vars = new LinkedHashMap<>();
                    Object vraw = fm.get("vars");
                    if (vraw instanceof Map) {
                        for (Map.Entry<?, ?> ve : ((Map<?, ?>) vraw).entrySet()) {
                            vars.put(String.valueOf(ve.getKey()), String.valueOf(ve.getValue()));
                        }
                    }
                    List<RewardAction> actions = new ArrayList<>();
                    Object araw = fm.get("actions");
                    if (araw instanceof List) {
                        int ai = 0;
                        for (Object ao : (List<?>) araw) {
                            ai++;
                            if (!(ao instanceof Map)) {
                                errors.add("rewards." + key + " 流程#" + fi + " 动作#" + ai + " 格式错误");
                                continue;
                            }
                            Map<String, Object> am = (Map<String, Object>) ao;
                            String atype = am.get("type") == null ? null : String.valueOf(am.get("type"));
                            if (atype == null || !KNOWN_ACTIONS.contains(atype)) {
                                errors.add("rewards." + key + " 流程#" + fi + " 动作#" + ai + " 未知动作类型: " + atype);
                                continue;
                            }
                            Map<String, Object> aparams = new LinkedHashMap<>(am);
                            aparams.remove("type");
                            actions.add(new RewardAction(atype, aparams));
                        }
                    }
                    flows.add(new RewardFlow(trigger, vars, actions));
                }
            }
            out.put(key, flows);
        }
        return out;
    }

    // ===================== 默认配置 =====================

    private void writeDefaultConfig(File file) {
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            plugin.saveResource("config.yml", false);
        } catch (Exception e) {
            plugin.getLogger().warning("复制内置 config.yml 失败，回退到内嵌字符串: " + e.getMessage());
            try {
                java.nio.file.Files.write(file.toPath(), DEFAULT_CONFIG.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ex) {
                plugin.getLogger().severe("写入默认 config.yml 失败: " + ex.getMessage());
            }
        }
    }

    /** 释放内置示例脚本到 scripts/ 目录（仅在首次运行时）。 */
    private void releaseBuiltinScripts(File scriptsDir) {
        try {
            if (!scriptsDir.exists()) scriptsDir.mkdirs();
            // 释放内置 rewards.lgs
            File lgsFile = new File(scriptsDir, "rewards.lgs");
            if (!lgsFile.exists()) {
                plugin.saveResource("scripts/rewards.lgs", false);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("释放内置脚本失败: " + e.getMessage());
        }
    }

    // ===================== 访问器 =====================

    public LgsScript getLgsScript() { return lgsScript; }

    public List<RewardFlow> getRewardFlows(String name) {
        return rewards.get(name);
    }

    public Object getConstant(String name) {
        return constants.get(name);
    }

    public Map<String, Object> getConstants() {
        return constants;
    }

    public int getMaxSteps() { return maxSteps; }

    public int getMaxEvalMs() { return maxEvalMs; }

    public boolean isAutoFlagEnabled() { return autoFlagEnabled; }

    public int getTimeoutSeconds() { return timeoutSeconds; }

    public int getFunctionCount() {
        return lgsScript.stepCount() + lgsScript.moduleCount();
    }

    public Persistence getPersistence() { return persistence; }

    // ===================== 默认配置文件内容 =====================

    private static final String DEFAULT_CONFIG = """
            # MineSweeper v4.0 配置文件
            # 奖励逻辑由 scripts/*.lgs 脚本文件定义（LogicStep 语言），不再使用 YAML 步骤。
            # 详见 DESIGN_LGS.md。

            game:
              auto_flag_enabled: false
              timeout_seconds: 60

            rewards:
              # 胜利时执行的流程列表
              win:
                - trigger: "logic:has_reward_permission"
                  vars:
                    amount: "logic:jiangli"
                  actions:
                    - type: console_command
                      command: "eco give {player} {amount}"
                    - type: message
                      message: "&6金币奖励：{amount}"
                - vars:
                    bonus_msg: "&e额外奖励已发放"
                  actions:
                    - type: give_chest
                      use_logic: "reward_chest_items"
                    - type: message
                      message: "&a你获得了奖励箱！{bonus_msg}"
              lose:
                - actions:
                    - type: message
                      message: "&c很遗憾，你输了！"

            logic:
              max_steps: 50000
              max_eval_ms: 50
              constants:
                base_eco: 100
            """;
}
