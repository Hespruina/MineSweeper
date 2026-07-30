package top.zhrhello.mineSweeper;

import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.ChatColor;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import top.zhrhello.mineSweeper.config.ConfigManager;
import top.zhrhello.mineSweeper.folia.SchedulerCompat;
import top.zhrhello.mineSweeper.logic.LogicEngine;
import top.zhrhello.mineSweeper.logic.Persistence;
import top.zhrhello.mineSweeper.rewards.RewardManager;

public final class MineSweeperPlugin extends JavaPlugin {
    // 线程安全的游戏存储
    private final List<MineSweeperGame> activeGames = new CopyOnWriteArrayList<>();
    private final Map<org.bukkit.Location, MineSweeperGame> locationToGame = new ConcurrentHashMap<>();

    // 可编程奖励系统与 Logic 引擎
    private ConfigManager configManager;
    private Persistence persistence;
    private LogicEngine logicEngine;
    private RewardManager rewardManager;

    /**
     * 检测当前是否运行在 Folia 服务端。委托 {@link SchedulerCompat} 统一实现，
     * 避免本类直接引用 Folia API（保证纯 paper-api 即可编译）。
     */
    public static boolean isFolia() {
        return SchedulerCompat.isFolia();
    }
    
    /**
     * 在正确的线程上执行任务 — 委托 {@link SchedulerCompat} 自动适配 Folia / Paper。
     *
     * <p>注意：传入的 task 若访问世界方块/实体，location 必须指向被访问的区块；
     * 若 task 需要操作跨越多个 region 的方块，应改用按区块分组的调用（见 {@code MineSweeperGame}）。
     *
     * @param location 用于确定 Folia 区域的 Location（Paper 上可为 null）
     * @param task 需要执行的 Runnable
     */
    void executeOnMainThread(Location location, Runnable task) {
        SchedulerCompat.runOnRegion(this, location, task);
    }

    @Override
    public void onEnable() {
        // 注册事件监听器
        getServer().getPluginManager().registerEvents(new MineSweeperListener(this), this);

        // 初始化可编程奖励系统与 Logic 逻辑引擎
        this.persistence = new Persistence(getDataFolder());
        this.configManager = new ConfigManager(this, persistence);
        this.logicEngine = new LogicEngine(configManager, persistence, this);
        this.rewardManager = new RewardManager(this, configManager, logicEngine);
        ConfigManager.ReloadResult rr = configManager.reload();
        if (rr.success) {
            getLogger().info("[MineSweeper] 配置加载成功：步骤 " + rr.stepCount + " 个，模块 "
                    + rr.moduleCount + " 个，动作 " + rr.actionCount + " 个，持久化条目 " + rr.persistenceCount + " 个"
                    + (rr.warnings.isEmpty() ? "" : "（警告 " + rr.warnings.size() + " 条）"));
            for (String w : rr.warnings) getLogger().warning("[MineSweeper] 配置警告: " + w);
        } else {
            getLogger().severe("[MineSweeper] 配置加载失败，使用空配置运行：");
            for (String e : rr.errors) getLogger().severe("  - " + e);
        }

        // 启动超时检查任务（每 20 ticks / 1 秒）。
        // 统一走 SchedulerCompat：Folia 下在全局区域线程回调（不访问具体世界/实体，
        // 仅读游戏状态字段并转发世界操作到对应 region）；Paper 下在主线程回调。
        SchedulerCompat.runOnGlobalAtFixedRate(this, this::tickGames, 20L, 20L);
    }
    
    /**
     * 处理所有活跃游戏的超时检查和倒计时。
     * Folia 上从 GlobalRegionScheduler（异步）调用，世界操作需通过 executeOnMainThread 转发。
     * Paper 上从 runTaskTimer（主线程）调用，世界操作可直接执行。
     */
    private void tickGames() {
        long currentTime = System.currentTimeMillis();
        for (MineSweeperGame game : new CopyOnWriteArrayList<>(activeGames)) {
            // 等待创建者"启动游戏"超时（15秒）：直接解散，避免无人开局却长期占用平台
            if (game.isActive() && game.isWaitingForStart() && (currentTime - game.getWaitingSince() > 15_000)) {
                if (!game.getPlatformBlocks().isEmpty()) {
                    Location loc = game.getPlatformBlocks().iterator().next();
                    executeOnMainThread(loc, () -> game.timeoutCancel());
                } else {
                    game.timeoutCancel();
                }
                continue;
            }
            // 检查无操作超时（60秒）；等待启动中的游戏不触发（由上面的 15 秒超时处理）
            if (game.isActive() && !game.isWaitingForExit() && !game.isWaitingForStart()
                    && (currentTime - game.getLastActivityTime() > 60_000)) {
                if (!game.getPlatformBlocks().isEmpty()) {
                    Location loc = game.getPlatformBlocks().iterator().next();
                    executeOnMainThread(loc, () -> game.endGame(false));
                }
            }
            // 处理退出倒计时
            if (game.isWaitingForExit()) {
                game.decrementExitCountdown();
                if (!game.getPlatformBlocks().isEmpty()) {
                    Location loc = game.getPlatformBlocks().iterator().next();
                    executeOnMainThread(loc, game::showCountdownToPlayers);
                }
                if (game.getExitCountdown() <= 0) {
                    if (!game.getPlatformBlocks().isEmpty()) {
                        Location loc = game.getPlatformBlocks().iterator().next();
                        executeOnMainThread(loc, () -> game.endGame(false));
                    }
                }
            }
            // ActionBar 视线提示 — 对活跃游戏每 tick 更新
            if (game.isActive() && !game.isWaitingForExit()) {
                if (!game.getPlatformBlocks().isEmpty()) {
                    Location loc = game.getPlatformBlocks().iterator().next();
                    executeOnMainThread(loc, game::updatePlayerActionBars);
                }
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("sweeper")) {
            // 热重载配置（权限 minesweeper.admin，控制台不受限）
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (sender instanceof Player && !sender.hasPermission("minesweeper.admin")) {
                    sender.sendMessage(ChatColor.RED + "你没有权限执行该命令");
                    return true;
                }
                ConfigManager.ReloadResult rr = configManager.reload();
                if (rr.success) {
                    sender.sendMessage(ChatColor.GREEN + "配置热重载成功！");
                    sender.sendMessage(ChatColor.YELLOW + "步骤: " + rr.stepCount
                            + " | 模块: " + rr.moduleCount
                            + " | 动作: " + rr.actionCount + " | 持久化条目: " + rr.persistenceCount);
                    if (!rr.warnings.isEmpty()) {
                        sender.sendMessage(ChatColor.GOLD + "警告 " + rr.warnings.size() + " 条：");
                        for (String w : rr.warnings) sender.sendMessage(ChatColor.GRAY + "  - " + w);
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + "配置重载失败，已保留旧配置。错误：");
                    for (String e : rr.errors) sender.sendMessage(ChatColor.RED + "  - " + e);
                }
                return true;
            }

            if (args.length == 0) {
                sender.sendMessage(ChatColor.RED + "用法: /sweeper <win|exit|see|list|reload> [序号]");
                return true;
            }
            
            // list命令不需要玩家执行，直接列出所有游戏
            if (args[0].equalsIgnoreCase("list")) {
                if (activeGames.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "当前没有正在进行的游戏");
                } else {
                    sender.sendMessage(ChatColor.GREEN + "当前正在进行的游戏:");
                    for (int i = 0; i < activeGames.size(); i++) {
                        MineSweeperGame game = activeGames.get(i);
                        Location loc = game.getPlatformBlocks().iterator().next();
                        sender.sendMessage(ChatColor.YELLOW + String.format("%d. 位置: (%d, %d, %d) 在世界 %s", 
                                i + 1, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), loc.getWorld().getName()));
                    }
                }
                return true;
            }
            
            // 其他命令都需要玩家执行
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "该命令只能由玩家执行");
                return true;
            }
            
            Player player = (Player) sender;
            
            // 获取目标游戏
            MineSweeperGame targetGame = null;
            int gameIndex = -1;
            
            // 如果提供了序号参数
            if (args.length > 1) {
                try {
                    gameIndex = Integer.parseInt(args[1]) - 1; // 转换为0基序号
                    if (gameIndex >= 0 && gameIndex < activeGames.size()) {
                        targetGame = activeGames.get(gameIndex);
                    } else {
                        sender.sendMessage(ChatColor.RED + "无效的游戏序号，请使用 /sweeper list 查看可用游戏");
                        return true;
                    }
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "序号必须是数字，请使用 /sweeper list 查看可用游戏");
                    return true;
                }
            } else {
                // 没有提供序号，尝试获取玩家所在位置的游戏
                Location loc = player.getLocation().clone();
                loc.setY(loc.getY() - 1); // 获取玩家下方的方块位置
                targetGame = getGameAt(loc);
                
                if (targetGame == null) {
                    if (activeGames.size() == 1) {
                        // 只有一个游戏时，自动选择那个游戏
                        targetGame = activeGames.get(0);
                    } else if (activeGames.size() > 1) {
                        // 有多个游戏时，要求提供序号
                        sender.sendMessage(ChatColor.RED + "有多个游戏正在进行，请使用 /sweeper list 查看游戏列表，并提供序号参数");
                        return true;
                    } else {
                        sender.sendMessage(ChatColor.RED + "当前没有正在进行的游戏");
                        return true;
                    }
                }
            }
            
            if (args[0].equalsIgnoreCase("win")) {
                // 强制游戏胜利
                if (targetGame != null && targetGame.isActive()) {
                    targetGame.endGame(true);
                    String gameInfo = gameIndex >= 0 ? "游戏 #" + (gameIndex + 1) : "当前游戏";
                    sender.sendMessage(ChatColor.GREEN + "已强制" + gameInfo + "胜利");
                } else {
                    sender.sendMessage(ChatColor.RED + "游戏无效或已结束");
                }
            } else if (args[0].equalsIgnoreCase("exit")) {
                // 强制退出游戏
                if (targetGame != null && targetGame.isActive()) {
                    targetGame.endGame(false);
                    String gameInfo = gameIndex >= 0 ? "游戏 #" + (gameIndex + 1) : "当前游戏";
                    sender.sendMessage(ChatColor.GREEN + "已强制退出" + gameInfo);
                } else {
                    sender.sendMessage(ChatColor.RED + "游戏无效或已结束");
                }
            } else if (args[0].equalsIgnoreCase("see")) {
                // 临时显示地雷3秒
                if (targetGame != null && targetGame.isActive()) {
                    targetGame.showMinesToPlayer(player, 3);
                    String gameInfo = gameIndex >= 0 ? "游戏 #" + (gameIndex + 1) : "当前游戏";
                    sender.sendMessage(ChatColor.GREEN + "正在为" + gameInfo + "显示地雷，持续3秒");
                } else {
                    sender.sendMessage(ChatColor.RED + "游戏无效或已结束");
                }
            } else {
                sender.sendMessage(ChatColor.RED + "用法: /sweeper <win|exit|see|list> [序号]");
            }
            
            return true;
        }
        
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("sweeper")) {
            return null;
        }

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // 第一层参数：子命令
            String prefix = args[0].toLowerCase();
            boolean isPlayer = sender instanceof Player;
            // reload 仅控制台或拥有 minesweeper.admin 的玩家可用，与其执行权限保持一致
            boolean canReload = !isPlayer || sender.hasPermission("minesweeper.admin");

            if ("list".startsWith(prefix)) completions.add("list");
            if (canReload && "reload".startsWith(prefix)) completions.add("reload");
            if (isPlayer) {
                if ("win".startsWith(prefix)) completions.add("win");
                if ("exit".startsWith(prefix)) completions.add("exit");
                if ("see".startsWith(prefix)) completions.add("see");
            }
        } else if (args.length == 2) {
            // 第二层参数：游戏序号（win/exit/see 需要）
            String sub = args[0].toLowerCase();
            if (sub.equals("win") || sub.equals("exit") || sub.equals("see")) {
                String prefix = args[1];
                for (int i = 1; i <= activeGames.size(); i++) {
                    String idx = String.valueOf(i);
                    if (idx.startsWith(prefix)) {
                        completions.add(idx);
                    }
                }
            }
        }

        return completions;
    }

    // 添加新游戏
    public ConfigManager getConfigManager() { return configManager; }
    public Persistence getPersistence() { return persistence; }
    public LogicEngine getLogicEngine() { return logicEngine; }
    public RewardManager getRewardManager() { return rewardManager; }

    // 添加新游戏
    public void addGame(MineSweeperGame game) {
        activeGames.add(game);
        game.getPlatformBlocks().forEach(loc ->
                locationToGame.put(loc.clone(), game)
        );
    }

    // 移除游戏
    public void removeGame(MineSweeperGame game) {
        activeGames.remove(game);
        game.getPlatformBlocks().forEach(loc ->
                locationToGame.remove(loc.clone())
        );
    }

    // 获取位置对应的游戏
    public MineSweeperGame getGameAt(org.bukkit.Location loc) {
        return locationToGame.get(loc.clone());
    }
    
    // 获取所有游戏位置
    public Set<Location> getAllGameLocations() {
        return new HashSet<>(locationToGame.keySet());
    }

    @Override
    public void onDisable() {
        // 关停前确保持久化落盘完成，避免丢失最后一次写入（每日上限累计值等）
        if (persistence != null) {
            persistence.flush();
        }
    }
}