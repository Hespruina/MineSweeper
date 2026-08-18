package top.zhrhello.mineSweeper;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
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

        // 蹲下放置 TNT 不触发游戏：允许玩家正常用 TNT 爆破自己的平台
        if (event.getPlayer().isSneaking()) return;

        Block below = event.getBlock().getRelative(BlockFace.DOWN);
        if (below.getType() != Material.GRAY_CONCRETE) return;

        // 检测平台连通区域
        Set<Location> platform = detectPlatform(below.getLocation());

        // 验证平台包含至少 5x5 的完整矩形区域（防止直线作弊）
        if (!hasMinimumArea(platform)) {
            event.getPlayer().sendMessage(ChatColor.RED + "平台必须包含完整的5x5矩形区域，不能是一条直线！");
            return;
        }

        // 先尝试清理上一局遗留的奖励箱子（以创建者身份模拟挖掘，QuickShop 会拦截他人的商店），
        // 清理后再检查平台上是否仍残留方块（如清理不掉的他人商店），有则拒绝开启游戏。
        clearLeftoverChests(platform, event.getPlayer());

        // 检查平台上是否仍残留其它方块（箱子、火把等），有则拒绝开启游戏
        Material leftover = findLeftoverBlock(platform);
        if (leftover != null) {
            if (leftover == Material.CHEST) {
                event.getPlayer().sendMessage(ChatColor.RED + "平台上仍有遗留的箱子，请先清理后再开启游戏！");
            } else {
                event.getPlayer().sendMessage(ChatColor.RED + "平台上仍有其它方块，请先清理后再开启游戏！");
            }
            return;
        }

        // 检查平台方块是否已被其他进行中的游戏占用
        for (Location loc : platform) {
            MineSweeperGame existing = plugin.getGameAt(loc);
            if (existing != null && existing.isActive()) {
                event.getPlayer().sendMessage(ChatColor.RED + "该平台部分方块已被其他进行中的游戏占用！");
                return;
            }
        }

        // 有效平台：收取TNT
        event.setCancelled(true);
        event.getBlockPlaced().setType(Material.AIR);

        // 创建新游戏
        MineSweeperGame game = new MineSweeperGame(
                plugin,
                platform,
                below.getLocation(),
                event.getPlayer()
        );
        plugin.addGame(game);
        
        // 显示教学GUI而不是直接开始游戏
        game.createTutorialGUI(event.getPlayer());
        playerGames.put(event.getPlayer().getUniqueId(), game);

        event.getPlayer().sendMessage(ChatColor.GREEN + "扫雷游戏已准备！请配置选项并启动游戏");
    }

    // 防止玩家在游戏平台上放置任何方块（TNT 由 onTNTPlace 专门处理，用于启动游戏）
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        // TNT 交给 onTNTPlace 处理，不要拦截（否则无法启动游戏）
        if (block.getType() == Material.TNT) return;

        Location loc = block.getLocation();
        // 检查放置位置本身，或放置位置下方一层是否为游戏平台方块。
        // 这样无论玩家是对着平台方块右键，还是对着旁边的墙/方块右键、
        // 让方块落在平台表面（格子上方一层），都会被拦截。
        MineSweeperGame game = plugin.getGameAt(loc);
        if (game == null) {
            game = plugin.getGameAt(loc.clone().add(0, -1, 0));
        }
        if (game != null && game.isActive()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "不能在此游戏平台上放置方块！");
        }
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
                if (!next.getWorld().equals(loc.getWorld()) || next.getBlockY() != loc.getBlockY()) {
                    continue;
                }
                if (platform.contains(next)) {
                    continue;
                }
                // 注意：这里不能再用 isOwnedByCurrentRegion 做遍历门控。
                // 原因：Luminol 等 Folia 分支缺少 World.isOwnedByCurrentRegion(Location) 方法，
                //   isOwnedByCurrentRegion 会恒返回 false，导致 BFS 被截断、platform 只剩起始方块，
                //   进而误报"平台必须包含 5x5 矩形区域"（即便平台实际是 6x6）。
                //   标准 Folia 上若平台跨 region 边界，同样会被截断。
                // 平台由玩家自己搭建、玩家就在旁边，chunk 必然已加载；对已加载 chunk 的 getType()
                // 只读访问在 Folia 上是安全的。用 try-catch 兜底，仅在 chunk 未加载等极端情况跳过该方块。
                Material type;
                try {
                    type = next.getBlock().getType();
                } catch (Throwable t) {
                    continue;
                }
                if (type == Material.GRAY_CONCRETE) {
                    platform.add(next.clone());
                    queue.add(next);
                }
            }
        }
        return platform;
    }

    /**
     * 检查平台是否包含至少一个完整的 5x5 矩形区域。
     * 防止玩家用直线（25 块一字排开）作弊通过大小检查。
     */
    private boolean hasMinimumArea(Set<Location> platform) {
        if (platform.size() < 25) return false;

        // 计算包围盒
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int y = 0;

        for (Location loc : platform) {
            int x = loc.getBlockX();
            int z = loc.getBlockZ();
            y = loc.getBlockY();
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }

        // 包围盒本身都不够 5x5，不可能存在
        if (maxX - minX + 1 < 5 || maxZ - minZ + 1 < 5) return false;

        World world = platform.iterator().next().getWorld();

        // 滑动窗口：扫描是否存在一个 5x5 子区域，其中 25 个方块全部在 platform 中
        for (int sx = minX; sx <= maxX - 4; sx++) {
            for (int sz = minZ; sz <= maxZ - 4; sz++) {
                boolean allPresent = true;
                for (int dx = 0; dx < 5; dx++) {
                    for (int dz = 0; dz < 5; dz++) {
                        Location check = new Location(world, sx + dx, y, sz + dz);
                        if (!platform.contains(check)) {
                            allPresent = false;
                            break;
                        }
                    }
                    if (!allPresent) break;
                }
                if (allPresent) return true;
            }
        }
        return false;
    }

    /**
     * 清理平台上遗留的奖励箱子：以 actor 身份「模拟挖掘」（触发 BlockBreakEvent），
     * 而不是直接 setType(AIR)。QuickShop 等保护插件会在事件中拦截属于他人的商店
     * （事件被取消、箱子保留），普通的遗留奖励箱子则按原版规则正常掉落。
     * 清理后仍残留的方块（如被拦截的他人商店）由 findLeftoverBlock 检出并拒绝开游戏。
     *
     * <p>线程说明：本方法由 {@code onTNTPlace} 直接同步调用，而 onTNTPlace 运行在
     * 玩家放置 TNT 的方块所属 region 线程（Paper 为主线程，Folia 为玩家实体所在 region，
     * 因玩家与刚放置的 TNT 同 region）。因此这里<b>不需要</b>再用 runOnRegionOwned 转发——
     * 那样反而会把 breakBlock 调度到「箱子方块」的 region 线程，导致 Folia 下跨 region
     * 访问玩家/方块。直接在当前线程同步挖掘，跨 region 的方块由 try-catch 兜底跳过
     * （该箱子残留，最终由 findLeftoverBlock 检出并拒绝开游戏，安全失败而非崩溃）。
     */
    private void clearLeftoverChests(Set<Location> platform, Player actor) {
        if (actor == null || !actor.isOnline()) return;
        for (Location loc : platform) {
            Location up = loc.clone().add(0, 1, 0);
            try {
                Block block = up.getBlock();
                if (block.getType() == Material.CHEST) {
                    // 触发 BlockBreakEvent，让 QuickShop 等保护插件有机会拦截
                    actor.breakBlock(block);
                }
            } catch (Throwable t) {
                // Folia 下平台跨 region 时，非本 region 的箱子会抛异常，跳过即可
                plugin.getLogger().warning("[MineSweeper] 清理遗留箱子异常: " + t.getMessage());
            }
        }
    }

    /**
     * 检查平台上方一层（火把/箱子层）是否仍有残留方块。
     * 正常游戏结束后平台会还原为灰色混凝土、火把被清除；但奖励箱子依赖玩家挖掘或
     * 下一局清理，若清理被保护插件拦截（如他人的 QuickShop 商店），箱子会保留在平台上。
     * 平台上方存在任何非空气方块（箱子/火把/其它）时返回该方块类型，否则返回 null。
     */
    private Material findLeftoverBlock(Set<Location> platform) {
        for (Location loc : platform) {
            Location up = loc.clone().add(0, 1, 0);
            Material type;
            try {
                // 只读 getType()：与 detectPlatform 同理，chunk 已加载时在 Folia 上安全
                type = up.getBlock().getType();
            } catch (Throwable t) {
                continue; // chunk 未加载等极端情况，跳过
            }
            if (type != Material.AIR) {
                return type;
            }
        }
        return null;
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
    
    // 处理引爆状态的 TNT：只要位于游戏平台上，直接移除（禁止在平台爆炸）
    @EventHandler
    public void onTNTPrimedSpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed)) return;

        Location loc = event.getEntity().getLocation();
        // 检查 TNT 实体自身所在位置，或其下方一层是否为游戏平台方块
        MineSweeperGame game = plugin.getGameAt(loc);
        if (game == null) {
            game = plugin.getGameAt(loc.clone().add(0, -1, 0));
        }
        if (game != null && game.isActive()) {
            event.getEntity().remove();
        }
    }

    // 防止TNT爆炸破坏游戏区域
    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        // 双保险：若爆炸中心位于游戏平台上，整体取消该次爆炸
        // （主要逻辑在 onTNTPrimedSpawn 中已移除引爆中的 TNT，这里兜底防止漏网）
        Location center = event.getLocation();
        MineSweeperGame game = plugin.getGameAt(center);
        if (game == null) {
            game = plugin.getGameAt(center.clone().add(0, -1, 0));
        }
        if (game != null && game.isActive()) {
            event.setCancelled(true);
            return;
        }

        // 平台外的爆炸：移除所有属于游戏平台的方块。
        // 保护范围 = 平台方块本身（格子层）+ 其正上方一层（红石火把、奖励箱子等），
        // 无论爆炸中心在不在平台上，平台和火把都不会被破坏。
        List<Block> blocksToRemove = new ArrayList<>();
        for (Block block : event.blockList()) {
            Location loc = block.getLocation();
            MineSweeperGame g = plugin.getGameAt(loc);
            if (g == null) {
                // 检查是否位于平台方块正上方一层（火把/箱子层）
                g = plugin.getGameAt(loc.clone().add(0, -1, 0));
            }
            if (g != null && g.isActive()) {
                blocksToRemove.add(block);
            }
        }
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
                
                // 处理取消游戏
                if (slot == 24) {
                    // 只有创建者才能取消游戏
                    if (game.getCreator() != null && !game.getCreator().equals(player)) {
                        player.sendMessage(ChatColor.RED + "只有创建者可以取消游戏");
                        player.closeInventory();
                        playerGames.remove(player.getUniqueId());
                        return;
                    }
                    // 先解散（置 waitingForStart=false）再关 GUI，避免关闭事件误判为"放弃创建"
                    game.timeoutCancel(ChatColor.RED + "游戏已取消，平台已释放");
                    player.closeInventory();
                    playerGames.remove(player.getUniqueId());
                    return;
                }

                // 处理启动游戏
                if (slot == 26) {
                    // 只有创建者才能启动游戏（理论上 GUI 仅对创建者开放，这里再次校验）
                    if (game.getCreator() != null && !game.getCreator().equals(player)) {
                        player.sendMessage(ChatColor.RED + "只有创建者可以启动游戏");
                        player.closeInventory();
                        playerGames.remove(player.getUniqueId());
                        return;
                    }
                    // 先启动（置 waitingForStart=false）再关 GUI，避免关闭事件误判为"放弃创建"
                    game.startGame();
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

        // 切换开关/难度时的"先关后开"属于程序化重开，不应清理关联/解散
        if (reopeningGUI.contains(player.getUniqueId())) return;

        MineSweeperGame game = playerGames.get(player.getUniqueId());
        playerGames.remove(player.getUniqueId());

        // 创建者直接关闭 GUI（如按 ESC）：视为放弃创建，立即解散游戏并释放平台
        if (game != null && game.isWaitingForStart()
                && game.getCreator() != null && game.getCreator().equals(player)) {
            game.timeoutCancel(ChatColor.RED + "已放弃创建，游戏已解散，平台已释放");
        }
    }

    // 玩家退出服务器：无论其 GUI 是否关闭，都清理关联，防止内存泄漏。
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // 若创建者在"等待启动"阶段退出，立即解散游戏，避免平台被长期占用
        MineSweeperGame game = playerGames.get(event.getPlayer().getUniqueId());
        if (game != null && game.isWaitingForStart() && game.getCreator() != null
                && game.getCreator().equals(event.getPlayer())) {
            game.timeoutCancel();
        }
        playerGames.remove(event.getPlayer().getUniqueId());
    }

}