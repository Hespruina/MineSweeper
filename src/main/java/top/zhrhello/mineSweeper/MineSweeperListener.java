package top.zhrhello.mineSweeper;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import java.util.*;

public class MineSweeperListener implements Listener {
    private final MineSweeperPlugin plugin;
    private final Map<UUID, MineSweeperGame> playerGames = new HashMap<>(); // 存储玩家和游戏的关联

    public MineSweeperListener(MineSweeperPlugin plugin) {
        this.plugin = plugin;
    }

    // 检测TNT放置触发游戏
    @EventHandler
    public void onTNTPlace(BlockPlaceEvent event) {
        if (event.getBlock().getType() != Material.TNT) return;

        Block below = event.getBlock().getRelative(BlockFace.DOWN);
        if (below.getType() != Material.GRAY_CONCRETE) return;

        // 检测平台连通区域
        Set<Location> platform = detectPlatform(below.getLocation());
        if (platform.size() < 25) {
            event.getPlayer().sendMessage(ChatColor.RED + "平台太小！至少需要5x5区域");
            return;
        }

        // 有效平台：收取TNT
        event.setCancelled(true);
        event.getBlockPlaced().setType(Material.AIR);

        // 创建新游戏
        MineSweeperGame game = new MineSweeperGame(
                plugin,
                platform,
                below.getLocation()
        );
        plugin.addGame(game);
        
        // 显示教学GUI而不是直接开始游戏
        game.createTutorialGUI(event.getPlayer());
        playerGames.put(event.getPlayer().getUniqueId(), game);

        event.getPlayer().sendMessage(ChatColor.GREEN + "扫雷游戏已准备！请配置选项并启动游戏");
    }

    // 平台检测 (BFS连通区域)
    private Set<Location> detectPlatform(Location start) {
        Set<Location> platform = new HashSet<>();
        Queue<Location> queue = new LinkedList<>();
        queue.add(start);
        platform.add(start.clone());

        while (!queue.isEmpty()) {
            Location loc = queue.poll();
            for (BlockFace face : Arrays.asList(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
                Location next = loc.clone().add(face.getModX(), face.getModY(), face.getModZ());
                if (next.getWorld().equals(loc.getWorld()) &&
                        next.getBlockY() == loc.getBlockY() &&
                        next.getBlock().getType() == Material.GRAY_CONCRETE &&
                        !platform.contains(next)) {
                    platform.add(next.clone());
                    queue.add(next);
                }
            }
        }
        return platform;
    }

    // 左/右键点击处理
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK &&
                event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block block = event.getClickedBlock();
        if (block == null) return;

        Location loc = block.getLocation();
        MineSweeperGame game = plugin.getGameAt(loc);
        if (game == null || !game.isActive()) return;

        event.setCancelled(true);

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            game.handleLeftClick(loc, event.getPlayer()); // 确保参数为 Location 和 Player
        } else {
            game.handleRightClick(loc, event.getPlayer()); // 确保参数为 Location 和 Player
        }
    }

    // 处理破坏方块事件
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Location loc = block.getLocation();
        MineSweeperGame game = plugin.getGameAt(loc);

        // 检查是否是奖励箱子
        if (block.getType() == Material.CHEST) {
            // 检查是否在游戏区域内
            for (Location platformLoc : plugin.getAllGameLocations()) {
                Location chestLoc = platformLoc.clone().add(0, 1, 0);
                if (chestLoc.equals(loc)) {
                    event.setCancelled(true);
                    event.getPlayer().sendMessage(ChatColor.RED + "不能破坏奖励箱子！");
                    return;
                }
            }
        }

        // 红石火把处理 (解旗)
        if (block.getType() == Material.REDSTONE_TORCH) {
            Location below = loc.clone().add(0, -1, 0);
            if (plugin.getGameAt(below) != null) {
                event.setCancelled(true);
                block.setType(Material.AIR);
                MineSweeperGame g = plugin.getGameAt(below);
                if (g != null && g.isActive()) {
                    g.handleFlagRemoval(loc);
                }
            }
            return;
        }

        // 破坏平台方块 → 紧急退出
        if (game != null && game.isActive()) {
            event.setCancelled(true);
            game.endGame(false);
            event.getPlayer().sendMessage(ChatColor.RED + "破坏平台！游戏终止");
        }
    }

    // 防止实体交互干扰
    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof ArmorStand) {
            Location loc = event.getRightClicked().getLocation();
            if (plugin.getGameAt(loc) != null) {
                event.setCancelled(true);
            }
        }
    }
    
    // 防止TNT爆炸破坏游戏区域
    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        List<Block> blocksToRemove = new ArrayList<>();
        for (Block block : event.blockList()) {
            Location loc = block.getLocation();
            MineSweeperGame game = plugin.getGameAt(loc);
            if (game != null && game.isActive()) {
                // 取消对该方块的破坏
                blocksToRemove.add(block);
            }
        }
        // 移除所有游戏区域内的方块
        event.blockList().removeAll(blocksToRemove);
    }
    
    // 处理活塞推出游戏方块
    @EventHandler
    public void onBlockPistonExtend(BlockPistonExtendEvent event) {
        // 检查移动的方块是否属于游戏区域
        for (Block block : event.getBlocks()) {
            Location loc = block.getLocation();
            MineSweeperGame game = plugin.getGameAt(loc);
            if (game != null && game.isActive()) {
                // 终止游戏
                game.endGame(false);
                // 移除活塞
                event.getBlock().setType(Material.AIR);
                return;
            }
        }
    }
    
    // 处理活塞收回游戏方块
    @EventHandler
    public void onBlockPistonRetract(BlockPistonRetractEvent event) {
        // 检查移动的方块是否属于游戏区域
        for (Block block : event.getBlocks()) {
            Location loc = block.getLocation();
            MineSweeperGame game = plugin.getGameAt(loc);
            if (game != null && game.isActive()) {
                // 终止游戏
                game.endGame(false);
                // 移除活塞
                event.getBlock().setType(Material.AIR);
                return;
            }
        }
    }
    
    // 处理GUI点击事件
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        
        Player player = (Player) event.getWhoClicked();
        Inventory inventory = event.getClickedInventory();
        
        // 检查是否是我们的GUI
        if (inventory != null && inventory.getSize() == 27 && inventory.getContents().length > 0) {
            // 通过检查特定槽位的物品来确认是否是我们的GUI
            ItemStack item = inventory.getItem(26); // 启动游戏按钮
            if (item != null && item.getType() == Material.TNT) {
                event.setCancelled(true); // 防止玩家拿走物品
                
                MineSweeperGame game = playerGames.get(player.getUniqueId());
                if (game == null) return;
                
                int slot = event.getRawSlot();
                ItemStack clickedItem = event.getCurrentItem();
                
                if (clickedItem == null || clickedItem.getType() == Material.AIR) return;
                
                // 处理自动标记开关
                if (slot == 13) {
                    game.toggleAutoFlag();
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
                    // 刷新GUI
                    player.closeInventory();
                    game.createTutorialGUI(player);
                    return;
                }
                
                // 处理难度选择
                if (slot >= 18 && slot <= 20) {
                    int difficulty = slot - 17; // 1=简单 2=中等 3=困难
                    game.setDifficulty(difficulty);
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
                    // 刷新GUI
                    player.closeInventory();
                    game.createTutorialGUI(player);
                    return;
                }
                
                // 处理启动游戏
                if (slot == 26) {
                    player.closeInventory();
                    player.sendMessage(ChatColor.GREEN + "游戏已启动！左键揭示，右键插旗");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                    // 移除玩家与游戏的关联，因为游戏已经启动
                    playerGames.remove(player.getUniqueId());
                    return;
                }
            }
        }
    }

}