package top.zhrhello.mineSweeper.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import top.zhrhello.mineSweeper.logic.LogicFunction;
import top.zhrhello.mineSweeper.logic.LogicException;
import top.zhrhello.mineSweeper.logic.Persistence;
import top.zhrhello.mineSweeper.logic.Step;

import java.io.File;
import java.util.*;

/**
 * 配置管理器：负责加载 / 热重载 config.yml，解析 logic 函数与 rewards 流程，
 * 执行静态检查（步骤语法、未知步骤/动作类型、缺少必填字段、函数调用指向未定义函数、
 * 循环引用检测），并在解析成功时原子替换内存中的配置引用。
 */
public class ConfigManager {

    private static final Set<String> KNOWN_STEPS = new HashSet<>(Arrays.asList(
            "set", "eval", "get_player", "get_difficulty", "get_platform_size", "get_mine_count",
            "get_auto_flag", "random_int", "random_chance", "check_permission", "eco_balance",
            "eco_give", "eco_take", "log", "notify_admin", "if", "while", "for", "break",
            "list_create", "list_add", "list_set", "list_get", "list_remove", "list_size",
            "map_create", "map_put", "map_get", "map_remove", "map_keys", "call", "return",
            "store_set", "store_get", "store_remove", "for_each"));

    private static final Set<String> KNOWN_ACTIONS = new HashSet<>(Arrays.asList(
            "console_command", "player_command", "message", "broadcast", "sound",
            "give_item", "give_chest", "delay"));

    private static final Set<String> META_KEYS = new HashSet<>(Arrays.asList("with", "out", "context", "->"));

    private final JavaPlugin plugin;
    private final Persistence persistence;

    // 当前生效配置（原子替换）
    private volatile Map<String, Object> constants = new LinkedHashMap<>();
    private volatile Map<String, LogicFunction> functions = new LinkedHashMap<>();
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
        public final int functionCount;
        public final int actionCount;
        public final int persistenceCount;

        ReloadResult(boolean success, List<String> errors, List<String> warnings,
                     int functionCount, int actionCount, int persistenceCount) {
            this.success = success;
            this.errors = errors;
            this.warnings = warnings;
            this.functionCount = functionCount;
            this.actionCount = actionCount;
            this.persistenceCount = persistenceCount;
        }

        static ReloadResult fail(List<String> errors, List<String> warnings) {
            return new ReloadResult(false, errors, warnings, 0, 0, 0);
        }

        static ReloadResult ok(int fc, int ac, int pc, List<String> warnings) {
            return new ReloadResult(true, new ArrayList<>(), warnings, fc, ac, pc);
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

        Map<String, Object> constantsParsed = new LinkedHashMap<>();
        ConfigurationSection constSec = cfg.getConfigurationSection("logic.constants");
        if (constSec != null) {
            constantsParsed.putAll(constSec.getValues(false));
        }
        int maxStepsParsed = cfg.getInt("logic.max_steps", 50000);
        if (maxStepsParsed <= 0) maxStepsParsed = 50000;
        int maxEvalMsParsed = cfg.getInt("logic.max_eval_ms", 50);
        if (maxEvalMsParsed <= 0) maxEvalMsParsed = 50;

        Map<String, LogicFunction> functionsParsed = parseFunctions(cfg, errors, warnings);
        Map<String, List<RewardFlow>> rewardsParsed = parseRewards(cfg, errors, warnings);

        boolean autoFlag = cfg.getBoolean("game.auto_flag_enabled", false);
        int timeout = cfg.getInt("game.timeout_seconds", 60);
        if (timeout <= 0) timeout = 60;

        if (!errors.isEmpty()) {
            return ReloadResult.fail(errors, warnings);
        }

        // 解析成功 → 原子替换
        synchronized (this) {
            this.constants = constantsParsed;
            this.functions = functionsParsed;
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
        return ReloadResult.ok(functionsParsed.size(), actionCount, persistence.size(), warnings);
    }

    // ===================== 解析：Logic 函数 =====================

    @SuppressWarnings("unchecked")
    private Map<String, LogicFunction> parseFunctions(YamlConfiguration cfg, List<String> errors, List<String> warnings) {
        Map<String, LogicFunction> out = new LinkedHashMap<>();
        ConfigurationSection fs = cfg.getConfigurationSection("logic.functions");
        if (fs == null) return out;
        for (String name : fs.getKeys(false)) {
            ConfigurationSection fsec = fs.getConfigurationSection(name);
            if (fsec == null) {
                errors.add("函数 " + name + " 定义格式错误（应为映射）");
                continue;
            }
            List<String> params = fsec.getStringList("parameters");
            List<?> rawSteps = fsec.getList("steps");
            if (rawSteps == null) {
                errors.add("函数 " + name + " 缺少 steps");
                continue;
            }
            List<Step> steps;
            try {
                steps = parseSteps(rawSteps, name, errors, warnings);
            } catch (Exception e) {
                errors.add("函数 " + name + " 解析异常: " + e.getMessage());
                continue;
            }
            out.put(name, new LogicFunction(name, params, steps));
        }
        validateCalls(out, errors, warnings);
        return out;
    }

    private List<Step> parseSteps(List<?> rawList, String funcName, List<String> errors, List<String> warnings) {
        List<Step> out = new ArrayList<>();
        int idx = 0;
        for (Object item : rawList) {
            idx++;
            if (!(item instanceof Map)) {
                errors.add("函数 " + funcName + " 步骤#" + idx + " 不是合法的步骤映射");
                continue;
            }
            Map<String, Object> itemMap = (Map<String, Object>) item;
            // 拆分：主步骤键 + 元信息键（with/out/context）
            String type = null;
            Object body = null;
            Map<String, Object> meta = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : itemMap.entrySet()) {
                if (META_KEYS.contains(e.getKey())) {
                    meta.put(e.getKey(), e.getValue());
                } else {
                    if (type != null) {
                        errors.add("函数 " + funcName + " 步骤#" + idx + " 存在多个步骤类型键");
                        type = null;
                        break;
                    }
                    type = e.getKey();
                    body = e.getValue();
                }
            }
            if (type == null) continue;
            Step step;
            try {
                step = Step.parse(new AbstractMap.SimpleEntry<>(type, body));
            } catch (Exception ex) {
                errors.add("函数 " + funcName + " 步骤#" + idx + " 解析失败: " + ex.getMessage());
                continue;
            }
            // 合并元信息
            if (meta.containsKey("out")) step.out = String.valueOf(meta.get("out"));
            if (meta.containsKey("->")) step.out = String.valueOf(meta.get("->"));
            if (meta.containsKey("with")) step.params.put("with", meta.get("with"));
            if (meta.containsKey("context")) step.params.put("context", meta.get("context"));

            validateStep(step, funcName, idx, errors, warnings);

            // 递归解析嵌套步骤
            if (step.type.equals("if")) {
                if (step.has("then")) step.params.put("then", parseSteps(toRawList(step.params.get("then")), funcName, errors, warnings));
                if (step.has("else")) step.params.put("else", parseSteps(toRawList(step.params.get("else")), funcName, errors, warnings));
            } else if (step.type.equals("while") || step.type.equals("for") || step.type.equals("for_each")) {
                if (step.has("do")) step.params.put("do", parseSteps(toRawList(step.params.get("do")), funcName, errors, warnings));
            }
            out.add(step);
        }
        return out;
    }

    private void validateStep(Step step, String funcName, int idx, List<String> errors, List<String> warnings) {
        String t = step.type;
        if (!KNOWN_STEPS.contains(t)) {
            errors.add("函数 " + funcName + " 步骤#" + idx + " 未知步骤类型: " + t);
            return;
        }
        String where = "函数 " + funcName + " 步骤#" + idx;
        switch (t) {
            case "if":
                if (!step.has("condition")) errors.add(where + " (if) 缺少 condition");
                if (!step.has("then")) errors.add(where + " (if) 缺少 then");
                break;
            case "while":
                if (!step.has("condition")) errors.add(where + " (while) 缺少 condition");
                if (!step.has("do")) errors.add(where + " (while) 缺少 do");
                break;
            case "for":
                if (!step.has("range")) errors.add(where + " (for) 缺少 range");
                if (!step.has("do")) errors.add(where + " (for) 缺少 do");
                break;
            case "for_each":
                if (!step.has("target")) errors.add(where + " (for_each) 缺少 target");
                if (!step.has("var")) errors.add(where + " (for_each) 缺少 var");
                if (!step.has("do")) errors.add(where + " (for_each) 缺少 do");
                break;
            case "call":
                if (!step.has("function")) errors.add(where + " (call) 缺少 function");
                break;
            case "return":
                if (step.out != null) warnings.add(where + " (return) 使用 out 捕获无效，已忽略");
                break;
            case "get_player":
            case "get_difficulty":
            case "get_platform_size":
            case "get_mine_count":
            case "get_auto_flag":
            case "list_get":
            case "list_size":
            case "map_get":
            case "map_keys":
                if (step.out == null) warnings.add(where + " (" + t + ") 缺少输出变量 (out)");
                break;
            case "eco_balance":
            case "random_int":
            case "random_chance":
            case "check_permission":
                if (!step.has("target")) warnings.add(where + " (" + t + ") 缺少输出变量 (target)");
                break;
            default:
                break;
        }
    }

    @SuppressWarnings("unchecked")
    private void validateCalls(Map<String, LogicFunction> funcs, List<String> errors, List<String> warnings) {
        // 调用目标必须存在
        for (LogicFunction f : funcs.values()) {
            for (Step s : f.steps) {
                collectCallTargets(s, funcs, errors);
            }
        }
        // 循环引用检测（仅警告，因为递归受深度限制允许存在）
        Set<String> visited = new HashSet<>();
        Set<String> stack = new HashSet<>();
        for (String name : funcs.keySet()) {
            if (!visited.contains(name)) {
                detectCycle(name, funcs, visited, stack, warnings);
            }
        }
    }

    private void collectCallTargets(Step s, Map<String, LogicFunction> funcs, List<String> errors) {
        if (s.type.equals("call")) {
            String fname = s.str("function");
            if (!funcs.containsKey(fname)) {
                errors.add("函数调用指向未定义的函数: " + fname);
            }
        }
        for (Step child : nestedSteps(s)) {
            collectCallTargets(child, funcs, errors);
        }
    }

    private boolean detectCycle(String name, Map<String, LogicFunction> funcs,
                                Set<String> visited, Set<String> stack, List<String> warnings) {
        visited.add(name);
        stack.add(name);
        LogicFunction f = funcs.get(name);
        if (f != null) {
            for (Step s : f.steps) {
                if (s.type.equals("call")) {
                    String target = s.str("function");
                    if (funcs.containsKey(target)) {
                        if (stack.contains(target)) {
                            warnings.add("检测到函数循环引用: " + name + " → " + target + "（递归受深度限制，请确保其可终止）");
                        } else if (!visited.contains(target)) {
                            detectCycle(target, funcs, visited, stack, warnings);
                        }
                    }
                }
                for (Step child : nestedSteps(s)) {
                    // 嵌套步骤里的 call 也已通过 s.type 处理（嵌套步骤本身不含 call 直接键）
                }
            }
        }
        stack.remove(name);
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<Step> nestedSteps(Step s) {
        List<Step> result = new ArrayList<>();
        if (s.has("then") && s.steps("then") != null) result.addAll(s.steps("then"));
        if (s.has("else") && s.steps("else") != null) result.addAll(s.steps("else"));
        if (s.has("do") && s.steps("do") != null) result.addAll(s.steps("do"));
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<?> toRawList(Object o) {
        return (o instanceof List) ? (List<?>) o : new ArrayList<>();
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
        // 优先从 jar 内置资源复制 config.yml（保证内置配置文件随插件分发）
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

    // ===================== 访问器 =====================

    public LogicFunction getFunction(String name) {
        return functions.get(name);
    }

    public List<RewardFlow> getRewardFlows(String name) {
        return rewards.get(name);
    }

    public Object getConstant(String name) {
        return constants.get(name);
    }

    public Map<String, Object> getConstants() {
        return constants;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public int getMaxEvalMs() {
        return maxEvalMs;
    }

    public boolean isAutoFlagEnabled() {
        return autoFlagEnabled;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public int getFunctionCount() {
        return functions.size();
    }

    public Persistence getPersistence() {
        return persistence;
    }

    // ===================== 默认配置文件内容 =====================

    private static final String DEFAULT_CONFIG = """
            # MineSweeper v3.0 配置文件
            # 所有奖励与逻辑均可通过本文件配置，无需修改 Java 代码。

            game:
              auto_flag_enabled: false
              timeout_seconds: 60

            rewards:
              # 胜利时执行的流程列表
              win:
                # 流程 1：金币奖励（使用 vars 变量绑定避免重复求值）
                - trigger: "logic:has_reward_permission"
                  vars:
                    amount: "logic:jiangli"
                  actions:
                    - type: console_command
                      command: "eco give {player} {amount}"
                    - type: message
                      message: "&6金币奖励：{amount}"
                # 流程 2：生成奖励箱（读取 Logic 函数返回值填充）
                - vars:
                    bonus_msg: "&e额外奖励已发放"
                  actions:
                    - type: give_chest
                      use_logic: "reward_chest_items"
                    - type: message
                      message: "&a你获得了奖励箱！{bonus_msg}"
              # 失败时执行的流程
              lose:
                - actions:
                    - type: message
                      message: "&c很遗憾，你输了！"

            logic:
              max_steps: 50000
              max_eval_ms: 50
              constants:
                base_eco: 100
              functions:
                # 是否有领取奖励权限
                has_reward_permission:
                  steps:
                    - get_player:
                        out: pname
                    - check_permission:
                        target: has_perm
                        permission: "minesweeper.reward"
                    - return: "{has_perm}"

                # 金币奖励计算
                jiangli:
                  steps:
                    - get_difficulty:
                        out: diff
                    - get_platform_size:
                        out: size
                    - eval:
                        target: raw_eco
                        expression: "{diff} * 50 + {size} * 2"
                    - check_permission:
                        target: has_vip
                        permission: "server.vip"
                    - if:
                        condition: "{has_vip} == 1"
                        then:
                          - random_chance:
                              target: lucky
                              percent: 33
                          - if:
                              condition: "{lucky} == 1"
                              then:
                                - set:
                                    target: eco_value
                                    value: "{raw_eco}"
                              else:
                                - set:
                                    target: eco_value
                                    value: 0
                        else:
                          - set:
                              target: eco_value
                              value: 0
                    - return: "{eco_value}"

                # 奖励箱物品列表
                reward_chest_items:
                  parameters: []
                  steps:
                    - get_difficulty:
                        out: diff
                    - get_platform_size:
                        out: size
                    - get_auto_flag:
                        out: autoflag
                    - set:
                        target: level
                        value: 0
                    - if:
                        condition: "{diff} == 1"
                        then:
                          - set:
                              target: level
                              value: 1
                    - if:
                        condition: "{diff} == 2"
                        then:
                          - set:
                              target: level
                              value: 2
                    - if:
                        condition: "{diff} == 3"
                        then:
                          - set:
                              target: level
                              value: 3
                    - if:
                        condition: "{size} > 100"
                        then:
                          - eval:
                              target: level
                              expression: "{level} + 1"
                    - if:
                        condition: "{size} <= 50"
                        then:
                          - eval:
                              target: level
                              expression: "{level} - 1"
                    - if:
                        condition: "{autoflag} == 1"
                        then:
                          - eval:
                              target: level
                              expression: "{level} - 1"
                    - if:
                        condition: "{level} < 0"
                        then:
                          - set:
                              target: level
                              value: 0
                    - call: weighted_item_list
                      with: [level]
                      out: items
                    - return: "{items}"

                weighted_item_list:
                  parameters: [reward_level]
                  steps:
                    - if:
                        condition: "{global.weights_init} != 1"
                        then:
                          - set:
                              target: global.weights_init
                              value: 1
                          - list_create:
                              target: global.mats
                          - list_add:
                              target: global.mats
                              value: "WHITE_CONCRETE"
                          - list_add:
                              target: global.mats
                              value: "IRON_INGOT"
                          - list_add:
                              target: global.mats
                              value: "GOLD_INGOT"
                          - list_add:
                              target: global.mats
                              value: "DIAMOND"
                          - set:
                              target: global.w_WHITE_CONCRETE
                              value: [20, 40, 60, 80]
                          - set:
                              target: global.w_IRON_INGOT
                              value: [0, 30, 50, 70]
                          - set:
                              target: global.w_GOLD_INGOT
                              value: [0, 0, 20, 40]
                          - set:
                              target: global.w_DIAMOND
                              value: [0, 0, 5, 15]
                    - list_create:
                        target: result
                    - set:
                        target: i
                        value: 0
                    - list_size:
                        target: global.mats
                        out: mat_count
                    - while:
                        condition: "{i} < {mat_count}"
                        do:
                          - list_get:
                              target: global.mats
                              index: "{i}"
                              out: mat_name
                          - eval:
                              target: weight_key
                              expression: '"global.w_" + {mat_name}'
                          - set:
                              target: weight_array
                              value: "{$weight_key}"
                          - list_get:
                              target: weight_array
                              index: "{reward_level}"
                              out: weight
                          - random_int:
                              target: rand
                              min: 1
                              max: 100
                          - if:
                              condition: "{rand} <= {weight}"
                              then:
                                - if:
                                    condition: "{mat_name} == \\"WHITE_CONCRETE\\""
                                    then:
                                      - call: random_concrete
                                        out: mat_name
                                - call: item_amount
                                  with: [mat_name, reward_level]
                                  out: amt
                                - eval:
                                    target: entry
                                    expression: '"{mat_name}" + ":" + {amt}'
                                - list_add:
                                    target: result
                                    value: "{entry}"
                          - eval:
                              target: i
                              expression: "{i} + 1"
                    - return: "{result}"

                random_concrete:
                  steps:
                    - list_create:
                        target: colors
                    - list_add:
                        target: colors
                        value: "WHITE_CONCRETE"
                    - list_add:
                        target: colors
                        value: "ORANGE_CONCRETE"
                    - list_add:
                        target: colors
                        value: "MAGENTA_CONCRETE"
                    - list_add:
                        target: colors
                        value: "LIGHT_BLUE_CONCRETE"
                    - list_add:
                        target: colors
                        value: "YELLOW_CONCRETE"
                    - list_add:
                        target: colors
                        value: "LIME_CONCRETE"
                    - list_add:
                        target: colors
                        value: "PINK_CONCRETE"
                    - list_add:
                        target: colors
                        value: "GRAY_CONCRETE"
                    - list_add:
                        target: colors
                        value: "LIGHT_GRAY_CONCRETE"
                    - list_add:
                        target: colors
                        value: "CYAN_CONCRETE"
                    - list_add:
                        target: colors
                        value: "PURPLE_CONCRETE"
                    - list_add:
                        target: colors
                        value: "BLUE_CONCRETE"
                    - list_add:
                        target: colors
                        value: "BROWN_CONCRETE"
                    - list_add:
                        target: colors
                        value: "GREEN_CONCRETE"
                    - list_add:
                        target: colors
                        value: "RED_CONCRETE"
                    - list_add:
                        target: colors
                        value: "BLACK_CONCRETE"
                    - list_size:
                        target: colors
                        out: len
                    - random_int:
                        target: idx
                        min: 0
                        max: "{len} - 1"
                    - list_get:
                        target: colors
                        index: "{idx}"
                        out: color
                    - return: "{color}"

                item_amount:
                  parameters: [material, level]
                  steps:
                    - eval:
                        target: base
                        expression: "{level} + 1"
                    - if:
                        condition: "{material} == \\"DIAMOND\\""
                        then:
                          - random_int:
                              target: amt
                              min: 1
                              max: "{base}"
                    - if:
                        condition: "{material} == \\"GOLD_INGOT\\""
                        then:
                          - random_int:
                              target: amt
                              min: 1
                              max: "{base} + 1"
                    - if:
                        condition: "{material} == \\"IRON_INGOT\\""
                        then:
                          - random_int:
                              target: amt
                              min: 1
                              max: "{base} + 2"
                    - if:
                        condition: "{material} != \\"DIAMOND\\" and {material} != \\"GOLD_INGOT\\" and {material} != \\"IRON_INGOT\\""
                        then:
                          - random_int:
                              target: amt
                              min: 1
                              max: "{base} + 3"
                    - return: "{amt}"
            """;
}
