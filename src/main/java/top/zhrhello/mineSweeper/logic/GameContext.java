package top.zhrhello.mineSweeper.logic;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Set;

/**
 * 一次奖励/Logic 触发的上下文快照。
 * 由 MineSweeperGame 在游戏结束时构建并传入奖励系统与 Logic 引擎。
 * 引擎中的 get_* 步骤以及 check_permission / eco_* 步骤都依赖此上下文。
 */
public class GameContext {
    public final Player player;
    public final int difficulty;       // 1-3，无游戏时为 0
    public final int platformSize;     // 平台方块总数
    public final int mineCount;        // 地雷数量
    public final boolean autoFlag;     // 自动标记是否开启
    public final Set<Location> platformBlocks; // 平台方块集合（用于箱子放置等）

    public GameContext(Player player, int difficulty, int platformSize, int mineCount,
                       boolean autoFlag, Set<Location> platformBlocks) {
        this.player = player;
        this.difficulty = difficulty;
        this.platformSize = platformSize;
        this.mineCount = mineCount;
        this.autoFlag = autoFlag;
        this.platformBlocks = platformBlocks;
    }
}
