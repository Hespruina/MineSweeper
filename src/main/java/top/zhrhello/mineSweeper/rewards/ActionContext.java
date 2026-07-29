package top.zhrhello.mineSweeper.rewards;

import top.zhrhello.mineSweeper.MineSweeperPlugin;
import top.zhrhello.mineSweeper.config.ConfigManager;
import top.zhrhello.mineSweeper.logic.GameContext;
import top.zhrhello.mineSweeper.logic.LogicEngine;

import java.util.Map;

/**
 * 奖励动作执行时的上下文：携带插件、引擎、配置、游戏上下文与已绑定的动作组变量。
 */
public class ActionContext {
    public final MineSweeperPlugin plugin;
    public final LogicEngine engine;
    public final ConfigManager config;
    public final GameContext gameCtx;
    public final Map<String, Object> vars; // 动作组变量（由 rewards.vars 绑定）

    public ActionContext(MineSweeperPlugin plugin, LogicEngine engine, ConfigManager config,
                         GameContext gameCtx, Map<String, Object> vars) {
        this.plugin = plugin;
        this.engine = engine;
        this.config = config;
        this.gameCtx = gameCtx;
        this.vars = vars;
    }
}
