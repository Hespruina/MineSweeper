package top.zhrhello.mineSweeper.logic;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import top.zhrhello.mineSweeper.config.ConfigManager;
import top.zhrhello.mineSweeper.logic.lgs.LgsHostBinding;
import top.zhrhello.mineSweeper.logic.lgs.LgsHostInstructions;
import top.zhrhello.mineSweeper.logic.lgs.LgsInterpreter;
import top.zhrhello.mineSweeper.logic.lgs.LgsScript;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Logic 逻辑引擎 —— 基于 LogicStep 脚本语言的解释器封装。
 *
 * 本类是 {@link LgsInterpreter} 的门面，保持与旧版相同的公共 API
 * （{@code execute} / {@code getGlobals} / {@code getGlobal}），
 * 使 {@link top.zhrhello.mineSweeper.rewards.RewardManager} 等调用方无需修改。
 *
 * <h3>架构变更</h3>
 * 旧版使用 YAML 步骤序列（{@code logic.functions}）作为脚本语言，
 * 新版改用 LogicStep 文本脚本（{@code .lgs} 文件），由 {@link LgsCompiler}
 * 编译、{@link LgsInterpreter} 执行。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>持有 {@link LgsHostBinding}（宿主指令绑定，含全局变量）</li>
 *   <li>每次执行时从 {@link ConfigManager} 获取编译后的 {@link LgsScript}，
 *       创建临时 {@link LgsInterpreter} 执行</li>
 *   <li>捕获运行时异常，通知管理员和玩家</li>
 * </ul>
 */
public class LogicEngine {

    private final ConfigManager config;
    private final Persistence persistence;
    private final JavaPlugin plugin;
    private final VaultHook vault;
    private final LgsHostInstructions hostBinding;

    public LogicEngine(ConfigManager config, Persistence persistence, JavaPlugin plugin) {
        this.config = config;
        this.persistence = persistence;
        this.plugin = plugin;
        this.vault = new VaultHook(plugin);
        this.hostBinding = new LgsHostInstructions(plugin, vault, persistence, config.getConstants());
    }

    /** 获取全局变量表（跨调用持久，重启丢失）。 */
    public Map<String, Object> getGlobals() {
        return hostBinding.getGlobals();
    }

    /** 获取指定全局变量的值。 */
    public Object getGlobal(String name) {
        return hostBinding.getGlobals().get(name);
    }

    // ===================== 顶层入口 =====================

    /**
     * 执行一个 LogicStep 步骤或模块。
     *
     * @param stepName 步骤/模块名
     * @param args     入口参数（可 null）
     * @param gameCtx  游戏上下文（玩家、难度等，可 null）
     * @return 步骤的返回值（失败或无返回时返回空串 ""）
     */
    public Object execute(String stepName, List<Object> args, GameContext gameCtx) {
        LgsScript script = config.getLgsScript();
        if (script == null || (script.stepCount() == 0 && script.moduleCount() == 0)) {
            plugin.getLogger().warning("[Logic] 无 LogicStep 脚本可执行（scripts/ 目录为空或加载失败）");
            return "";
        }

        // 检查步骤/模块是否存在
        if (script.getStep(stepName) == null && script.getModule(stepName) == null) {
            plugin.getLogger().warning("[Logic] 未找到步骤或模块: " + stepName);
            return "";
        }

        // 更新常量引用（reload 后常量可能变化）
        hostBinding.updateConstants(config.getConstants());

        LgsInterpreter interpreter = new LgsInterpreter(
                script, hostBinding, persistence, plugin,
                config.getMaxSteps(), config.getMaxEvalMs());

        try {
            return interpreter.execute(stepName, args == null ? new ArrayList<>() : args, gameCtx);
        } catch (LogicException e) {
            plugin.getLogger().log(Level.SEVERE, "[Logic] 执行步骤 '" + stepName + "' 出错: " + e.getMessage());
            if (gameCtx != null && gameCtx.player != null) {
                notifyAdmins("§c[扫雷] LogicStep 脚本执行出错: " + e.getMessage());
                gameCtx.player.sendMessage("§c很抱歉，奖励脚本执行时出现问题，已通知管理员。");
            }
            return "";
        }
    }

    private void notifyAdmins(String msg) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("minesweeper.admin")) {
                p.sendMessage(msg);
            }
        }
        plugin.getLogger().info(msg);
    }
}
