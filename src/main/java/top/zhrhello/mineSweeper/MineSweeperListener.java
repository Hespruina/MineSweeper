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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MineSweeperListener implements Listener {
    private final MineSweeperPlugin plugin;
    // 存储玩家和游戏的关联（GUI 配置阶段使用）。
    // 使用 ConcurrentHashMap 仅为与插件整体并发风格保持一致；事件均在主线程触发。
    private final Map<UUID, MineSweeperGame> playerGames = new ConcurrentHashMap<>();
    // 标记"程序化重开 GUI"的关闭事件：切换开关/难度时会先关闭再重开，
    // 此时不应清理 playerGames 关联，否则重开后点击会找不到游戏。
    private final Set<UUID> reopeningGUI = Collections.newSetFromMap(new ConcurrentHashMap<>());

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
            game.handleLeftClick(loc, event.getPlayer());
        } else {
            // 空手右键客户端不发送事件，因此拦截所有手持物品右键作为插旗
            game.handleRightClick(loc, event.getPlayer());
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
                    // 刷新GUI：先标记重开，关闭触发 InventoryCloseEvent 时不会被误清理
                    reopeningGUI.add(player.getUniqueId());
                    player.closeInventory();
                    game.createTutorialGUI(player);
                    reopeningGUI.remove(player.getUniqueId());
                    return;
                }
                
                // 处理难度选择
                if (slot >= 18 && slot <= 20) {
                    int difficulty = slot - 17; // 1=简单 2=中等 3=困难
                    game.setDifficulty(difficulty);
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
                    // 刷新GUI：同上，避免关闭事件误清理关联
                    reopeningGUI.add(player.getUniqueId());
                    player.closeInventory();
                    game.createTutorialGUI(player);
                    reopeningGUI.remove(player.getUniqueId());
                    return;
                }
                
                // 处理启动游戏
                if (slot == 26) {
                    player.closeInventory();
                    player.sendMessage(ChatColor.GREEN + "游戏已启动！左键揭示，右键插旗");
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                    // 移除玩家与游戏的关联，因为游戏已经启动
                    // （关闭事件也会清理，这里作为兜底）
                    playerGames.remove(player.getUniqueId());
                    return;
                }
            }
        }
    }

    // 判断某个 Inventory 是否为我们的教学/设置 GUI（27 格且启动按钮为 TNT）
    private boolean isOurGUI(Inventory inventory) {
        if (inventory == null || inventory.getSize() != 27) return false;
        ItemStack item = inventory.getItem(26);
        return item != null && item.getType() == Material.TNT;
    }

    // 玩家关闭 GUI：无论是按 ESC 退出、被其他插件强制关闭，还是正常启动游戏，
    // 只要关闭的是我们的 GUI 且不是"程序化重开"，就清理 playerGames 关联，
    // 避免 MineSweeperGame 对象被 Map 一直引用而无法被 GC 回收。
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();

        if (!isOurGUI(event.getInventory())) return;

        // 切换开关/难度时的"先关后开"属于程序化重开，不应清理关联
        if (reopeningGUI.contains(player.getUniqueId())) return;

        playerGames.remove(player.getUniqueId());
    }

    // 玩家退出服务器：无论其 GUI 是否关闭，都清理关联，防止内存泄漏。
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerGames.remove(event.getPlayer().getUniqueId());
    }

}