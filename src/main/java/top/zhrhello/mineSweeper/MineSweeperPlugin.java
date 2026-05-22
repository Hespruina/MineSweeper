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

public final class MineSweeperPlugin extends JavaPlugin {
    // 线程安全的游戏存储
    private final List<MineSweeperGame> activeGames = new CopyOnWriteArrayList<>();
    private final Map<org.bukkit.Location, MineSweeperGame> locationToGame = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        // 注册事件监听器
        getServer().getPluginManager().registerEvents(new MineSweeperListener(this), this);

        // 启动超时检查任务 (每20 ticks/1秒检查)
        try {
            // 尝试使用Folia的区域调度器
            getClass().getClassLoader().loadClass("io.papermc.paper.threadedregions.RegionizedServer");
            // 如果我们能加载到Folia的类，就使用全局区域调度
            getServer().getGlobalRegionScheduler().runAtFixedRate(this, (task) -> {
                long currentTime = System.currentTimeMillis();
                for (MineSweeperGame game : new CopyOnWriteArrayList<>(activeGames)) {
                    // 检查无操作超时（60秒）
                    if (game.isActive() && !game.isWaitingForExit() && (currentTime - game.getLastActivityTime() > 60_000)) {
                        // 使用实体调度器在主线程中执行endGame
                        if (!game.getPlatformBlocks().isEmpty()) {
                            org.bukkit.Location loc = game.getPlatformBlocks().iterator().next();
                            getServer().getRegionScheduler().execute(this, loc.getWorld(), 
                                    loc.getBlockX() >> 4, loc.getBlockZ() >> 4, 
                                    () -> game.endGame(false));
                        }
                    }
                    // 处理退出倒计时
                    if (game.isWaitingForExit()) {
                        game.decrementExitCountdown();
                        // 使用实体调度器在主线程中显示倒计时
                        if (!game.getPlatformBlocks().isEmpty()) {
                            org.bukkit.Location loc = game.getPlatformBlocks().iterator().next();
                            getServer().getRegionScheduler().execute(this, loc.getWorld(), 
                                    loc.getBlockX() >> 4, loc.getBlockZ() >> 4, 
                                    game::showCountdownToPlayers);
                        }
                        if (game.getExitCountdown() <= 0) {
                            // 使用实体调度器在主线程中执行endGame
                            if (!game.getPlatformBlocks().isEmpty()) {
                                org.bukkit.Location loc = game.getPlatformBlocks().iterator().next();
                                getServer().getRegionScheduler().execute(this, loc.getWorld(), 
                                        loc.getBlockX() >> 4, loc.getBlockZ() >> 4, 
                                        () -> game.endGame(false));
                            }
                        }
                    }
                }
            }, 20L, 20L);
        } catch (ClassNotFoundException e) {
            // 回退到传统的Bukkit调度器
            getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
                long currentTime = System.currentTimeMillis();
                for (MineSweeperGame game : new CopyOnWriteArrayList<>(activeGames)) {
                    // 检查无操作超时（60秒）
                    if (game.isActive() && !game.isWaitingForExit() && (currentTime - game.getLastActivityTime() > 60_000)) {
                        game.endGame(false);
                    }
                    // 处理退出倒计时
                    if (game.isWaitingForExit()) {
                        game.decrementExitCountdown();
                        // 在Bukkit环境下直接调用显示倒计时
                        game.showCountdownToPlayers();
                        if (game.getExitCountdown() <= 0) {
                            game.endGame(false);
                        }
                    }
                }
            }, 20L, 20L);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("sweeper")) {
            if (args.length == 0) {
                sender.sendMessage(ChatColor.RED + "用法: /sweeper <win|exit|see|list> [序号]");
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
}