package top.zhrhello.mineSweeper;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.FluidCollisionMode;
import top.zhrhello.mineSweeper.logic.GameContext;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class MineSweeperGame {
    private final MineSweeperPlugin plugin;
    private final Set<Location> platformBlocks;
    private final Map<Location, Boolean> isMine;
    private final Map<Location, Integer> numbers;
    private final Set<Location> flaggedBlocks;
    private final Set<Location> revealedBlocks;
    private final Location startLocation;
    private long lastActivityTime;
    private boolean active;
    private boolean waitingForExit;
    private final int mineCount;
    private int exitCountdown = -1; // 退出倒计时（秒）
    private boolean firstClick = true; // 标记是否为首次点击
    private boolean autoFlagEnabled = false; // 自动标记开关
    private int customMineCount; // 自定义雷数
    private int difficulty = 1; // 难度等级 1=简单 2=中等 3=困难
    private Player lastActor;    // 最近操作游戏的玩家（用于奖励上下文）
    private Player creator;                 // 创建者（放置 TNT 的玩家）；需先点击"启动游戏"游戏才算开始
    private boolean waitingForStart = true; // 等待创建者点击"启动游戏"；此状态下禁止翻格/插旗
    private long waitingSince;              // 进入等待启动状态的时间戳（用于 15 秒超时解散）

    public MineSweeperGame(MineSweeperPlugin plugin, Set<Location> platformBlocks, Location startLocation, Player creator) {
        this.plugin = plugin;
        this.platformBlocks = Collections.unmodifiableSet(new HashSet<>(platformBlocks));
        this.startLocation = startLocation.clone();
        this.creator = creator;
        this.isMine = new HashMap<>();
        this.numbers = new HashMap<>();
        this.flaggedBlocks = new HashSet<>();
        this.revealedBlocks = new HashSet<>();
        this.lastActivityTime = System.currentTimeMillis();
        this.active = true;
        this.waitingForExit = false;
        this.waitingForStart = true;
        this.waitingSince = System.currentTimeMillis();

        // 计算雷数 (15% of platform size, min 1)
        int totalBlocks = platformBlocks.size();
        this.mineCount = Math.max(1, (int) (totalBlocks * 0.15));
        this.customMineCount = this.mineCount;

        // 新一局开始时，先把上一局遗留的奖励箱子破坏并掉落其内容与箱子本身，
        // 解决玩家有时不挖箱子、导致奖励丢失的问题
        clearLeftoverChests();

        // 初始化平台为灰色混凝土
        initializePlatform();
        
        // 显示游戏开始title
        showStartTitle();
    }

    private void initializePlatform() {
        for (Location loc : platformBlocks) {
            loc.getBlock().setType(Material.GRAY_CONCRETE);
        }
    }

    // 新一局开始时清理上一局遗留的奖励箱子：将其与箱内物品全部变为掉落物。
    // 这样即便玩家上一局没有挖掉箱子，奖励也不会丢——新游戏一开局就掉在地上可拾取。
    private void clearLeftoverChests() {
        for (Location loc : platformBlocks) {
            Location up = loc.clone().add(0, 1, 0);
            Block block = up.getBlock();
            if (block.getType() != Material.CHEST) continue;

            Chest chest = (Chest) block.getState();
            Location dropLoc = up.clone().add(0.5, 0.5, 0.5);

            // 掉落箱子本身
            block.getWorld().dropItemNaturally(dropLoc, new ItemStack(Material.CHEST));
            // 掉落箱内所有物品
            for (ItemStack item : chest.getInventory().getContents()) {
                if (item != null && item.getType() != Material.AIR) {
                    block.getWorld().dropItemNaturally(dropLoc, item);
                }
            }
            // 清空并移除箱子方块
            chest.getInventory().clear();
            block.setType(Material.AIR);
        }
    }

    private void showStartTitle() {
        for (Location loc : platformBlocks) {
            Location upLoc = loc.clone().add(0, 1, 0);
            Collection<Entity> entities = upLoc.getWorld().getNearbyEntities(upLoc, 0.5, 0.5, 0.5);
            for (Entity entity : entities) {
                if (entity instanceof Player) {
                    Player player = (Player) entity;
                    player.sendTitle(ChatColor.GOLD + "扫雷游戏", "雷数: " + customMineCount, 10, 40, 10);
                    player.sendMessage(ChatColor.GREEN + "游戏提示: 左键翻开方块，右键插旗标记地雷");
                    player.sendMessage(ChatColor.GREEN + "数字表示周围8个格子中地雷的数量");
                }
            }
        }
    }

    // 首次点击时放置地雷，仅保证首次点击的格子本身不是雷
    // 标准规则下不保证周围 8 格也无雷，因此首点可能直接显示 1~8 的数字
    private void placeMines(Location firstClickLoc) {
        // 仅排除起始位置本身（不再排除周围 8 格，使首次点开可能为数字格）
        List<Location> safeBlocks = new ArrayList<>(platformBlocks);
        safeBlocks.remove(firstClickLoc);

        Collections.shuffle(safeBlocks, ThreadLocalRandom.current());

        // 放置雷 (使用自定义雷数)
        for (int i = 0; i < customMineCount && i < safeBlocks.size(); i++) {
            isMine.put(safeBlocks.get(i), true);
        }

        // 计算数字
        for (Location loc : platformBlocks) {
            if (!isMine.getOrDefault(loc, false)) {
                numbers.put(loc, countMinesAround(loc));
            }
        }
    }

    private int countMinesAround(Location loc) {
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                Location neighbor = loc.clone().add(dx, 0, dz);
                if (platformBlocks.contains(neighbor) && isMine.getOrDefault(neighbor, false)) {
                    count++;
                }
            }
        }
        return count;
    }

    public void handleLeftClick(Location loc, Player player) {
        // 验证参数有效性
        if (loc == null || player == null) {
            return;
        }
        this.lastActor = player;

        // 等待创建者点击"启动游戏"期间，禁止翻格，提示玩家等待
        if (waitingForStart) {
            player.sendMessage(ChatColor.YELLOW + "请等待游戏开始");
            return;
        }

        if (!active || waitingForExit) {
            lastActivityTime = System.currentTimeMillis();
            // 如果在退出倒计时阶段点击，则重置倒计时
            if (waitingForExit) {
                exitCountdown = 10;
                showExitCountdown(player);
            }
            return;
        }

        // 创建位置副本以防止外部修改
        Location clickedLoc = loc.clone();

        // 检查是否插旗
        if (flaggedBlocks.contains(clickedLoc)) {
            // 如果已经标记旗帜，允许连锁翻开
            if (revealedBlocks.contains(clickedLoc)) {
                int number = numbers.getOrDefault(clickedLoc, 0);
                if (number > 0) {
                    int flaggedNeighbors = countFlaggedNeighbors(clickedLoc);
                    if (flaggedNeighbors == number) {
                        revealNeighbors(clickedLoc, player);
                        // 连锁翻开后执行一次自动标雷
                        if (autoFlagEnabled && isLargeEnoughForAutoFlag() && active && !waitingForExit) {
                            autoFlagMines();
                        }
                        player.playSound(player.getLocation(), Sound.BLOCK_STONE_BUTTON_CLICK_ON, 1.0f, 1.0f);
                    } else if (flaggedNeighbors > number) {
                        player.sendMessage(ChatColor.RED + "标记数超过提示数字，请检查标记!");
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f);
                        return;
                    }
                }
            }
            return;
        }

        // 更新最后活动时间
        lastActivityTime = System.currentTimeMillis();

        // 首次点击时放置地雷
        if (firstClick) {
            firstClick = false;
            placeMines(clickedLoc);
            player.sendMessage(ChatColor.GREEN + "游戏开始! 难度: " + getDifficultyColor() + getDifficultyName() + 
                             ChatColor.GREEN + ", 地雷数: " + ChatColor.YELLOW + customMineCount);
        }

        // 点击到雷
        if (isMine.getOrDefault(clickedLoc, false)) {
            revealAllMines();
            waitingForExit = true;
            exitCountdown = 10; // 设置10秒退出倒计时
            showExitCountdown(player);
            player.sendMessage(ChatColor.RED + "BOOM! 点击任意方块退出游戏");
            player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);
            return;
        }

        // 揭示安全方块
        revealBlock(clickedLoc);
        // 自动标记功能 - 仅在用户主动点击后执行一次（而非 BFS 展开时重复调用）
        if (autoFlagEnabled && isLargeEnoughForAutoFlag()) {
            autoFlagMines();
        }
        player.playSound(player.getLocation(), Sound.BLOCK_STONE_BUTTON_CLICK_OFF, 1.0f, 1.0f);

        // 检查胜利
        if (checkWin()) {
            endGame(true);
            player.sendMessage(ChatColor.GOLD + "扫雷成功！");
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        }
    }

    private void revealBlock(Location loc) {
        if (!platformBlocks.contains(loc) ||
                isMine.getOrDefault(loc, false) ||
                flaggedBlocks.contains(loc) ||
                revealedBlocks.contains(loc)) {
            return;
        }

        revealedBlocks.add(loc);
        int number = numbers.getOrDefault(loc, 0);
        setNumberBlock(loc, number);

        // 自动展开0区域
        if (number == 0) {
            Queue<Location> queue = new LinkedList<>();
            queue.add(loc);
            Set<Location> visited = new HashSet<>();

            while (!queue.isEmpty()) {
                Location current = queue.poll();
                if (!visited.add(current)) continue;

                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        Location neighbor = current.clone().add(dx, 0, dz);
                        if (platformBlocks.contains(neighbor) && 
                            !isMine.getOrDefault(neighbor, false) && 
                            !flaggedBlocks.contains(neighbor) && 
                            !revealedBlocks.contains(neighbor)) {
                            revealedBlocks.add(neighbor);
                            int n = numbers.getOrDefault(neighbor, 0);
                            setNumberBlock(neighbor, n);
                            if (n == 0) queue.add(neighbor);
                        }
                    }
                }
            }
        }
    }

    // 检查游戏区域是否足够大以启用自动标记
    private boolean isLargeEnoughForAutoFlag() {
        // 计算平台的最小边界和最大边界
        if (platformBlocks.isEmpty()) {
            return false;
        }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (Location loc : platformBlocks) {
            int x = loc.getBlockX();
            int z = loc.getBlockZ();
            
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }

        int width = maxX - minX + 1;
        int length = maxZ - minZ + 1;

        // 只有当宽度和长度都大于等于10时才启用自动标记
        return width >= 10 && length >= 10;
    }

    // 计算标记的邻居数
    private int countFlaggedNeighbors(Location loc) {
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                Location neighbor = loc.clone().add(dx, 0, dz);
                if (platformBlocks.contains(neighbor) && flaggedBlocks.contains(neighbor)) {
                    count++;
                }
            }
        }
        return count;
    }

    // 计算未翻开的邻居数
    private int countUnrevealedNeighbors(Location loc) {
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                Location neighbor = loc.clone().add(dx, 0, dz);
                if (platformBlocks.contains(neighbor) && 
                    !revealedBlocks.contains(neighbor) && 
                    !flaggedBlocks.contains(neighbor)) {
                    count++;
                }
            }
        }
        return count;
    }

    // 自动标记功能 - 削弱版
    private void autoFlagMines() {
        boolean changed = false;

        // 只创建一次快照以避免 ConcurrentModificationException（循环体内 revealBlock
        // 会向 revealedBlocks 添加元素）；两段逻辑共用同一份快照，无需复制两次。
        Set<Location> snapshot = new HashSet<>(revealedBlocks);

        // 削弱自动标记功能 - 只处理确定性标记（只当未标记邻居数等于剩余雷数时才标记）
        // 遍历所有已翻开的数字格子
        for (Location loc : snapshot) {
            int number = numbers.getOrDefault(loc, 0);
            if (number > 0) {
                // 计算未标记的邻居数
                int unrevealedNeighbors = countUnrevealedNeighbors(loc);
                int flaggedNeighbors = countFlaggedNeighbors(loc);
                int remainingMines = number - flaggedNeighbors; // 剩余雷数

                // 只有当未翻开邻居数等于剩余雷数时才进行标记
                if (unrevealedNeighbors == remainingMines && remainingMines > 0) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dz == 0) continue;
                            Location neighbor = loc.clone().add(dx, 0, dz);
                            // 只有当邻居位置确实有地雷时才放置标记
                            if (platformBlocks.contains(neighbor) &&
                                !revealedBlocks.contains(neighbor) &&
                                !flaggedBlocks.contains(neighbor) &&
                                isMine.getOrDefault(neighbor, false)) {
                                placeFlag(neighbor);
                                changed = true;
                            }
                        }
                    }
                }
            }
        }

        // 如果有新的标记被放置，检查是否可以触发连锁翻开（复用同一份快照）
        if (changed) {
            for (Location loc : snapshot) {
                int number = numbers.getOrDefault(loc, 0);
                if (number > 0) {
                    int flaggedNeighbors = countFlaggedNeighbors(loc);
                    int unrevealedNeighbors = countUnrevealedNeighbors(loc);

                    // 如果标记数等于提示数字，且还有未翻开的方块
                    if (flaggedNeighbors == number && unrevealedNeighbors > 0) {
                        // 翻开所有未标记的邻居
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                if (dx == 0 && dz == 0) continue;
                                Location neighbor = loc.clone().add(dx, 0, dz);
                                if (platformBlocks.contains(neighbor) &&
                                    !revealedBlocks.contains(neighbor) &&
                                    !flaggedBlocks.contains(neighbor)) {
                                    // 不要翻开地雷
                                    if (!isMine.getOrDefault(neighbor, false)) {
                                        revealBlock(neighbor);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 翻开邻居
    private void revealNeighbors(Location loc, Player player) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                Location neighbor = loc.clone().add(dx, 0, dz);
                if (platformBlocks.contains(neighbor) && !flaggedBlocks.contains(neighbor)) {
                    if (isMine.getOrDefault(neighbor, false)) {
                        // 点击到雷
                        revealAllMines();
                        waitingForExit = true;
                        exitCountdown = 10; // 设置10秒退出倒计时
                        showExitCountdown(player);
                        player.sendMessage(ChatColor.RED + "BOOM! 点击任意方块退出游戏");
                        return;
                    } else {
                        revealBlock(neighbor);
                    }
                }
            }
        }
        
        // 检查胜利
        if (checkWin()) {
            endGame(true);
            player.sendMessage(ChatColor.GOLD + "扫雷成功！");
        }
    }

    private void setNumberBlock(Location loc, int number) {
        Material material;
        switch (number) {
            case 0: material = Material.WHITE_CONCRETE; break;
            case 1: material = Material.LIGHT_BLUE_CONCRETE; break;
            case 2: material = Material.GREEN_CONCRETE; break;
            case 3: material = Material.RED_CONCRETE; break;
            case 4: material = Material.PURPLE_CONCRETE; break;
            case 5: material = Material.ORANGE_CONCRETE; break;
            case 6: material = Material.LIME_CONCRETE; break;
            case 7: material = Material.BROWN_CONCRETE; break;
            case 8: material = Material.BLACK_CONCRETE; break;
            default: material = Material.WHITE_CONCRETE;
        }
        loc.getBlock().setType(material);
    }

    private void revealAllMines() {
        for (Location loc : platformBlocks) {
            if (isMine.getOrDefault(loc, false)) {
                loc.getBlock().setType(Material.TNT);
            }
        }
    }

    // 临时显示地雷给特定玩家
    public void showMinesToPlayer(Player player, int durationSeconds) {
        // 记录玩家原来位置的方块类型
        Map<Location, Material> originalBlocks = new HashMap<>();
        
        for (Location loc : platformBlocks) {
            if (isMine.getOrDefault(loc, false)) {
                originalBlocks.put(loc, loc.getBlock().getType());
                loc.getBlock().setType(Material.TNT);
            }
        }
        
        // 延迟恢复原来的方块类型 — 使用统一的 Folia/Paper 检测和调度
        if (!platformBlocks.isEmpty()) {
            Location loc = platformBlocks.iterator().next();
            if (MineSweeperPlugin.isFolia()) {
                plugin.getServer().getRegionScheduler().runDelayed(plugin, loc, (scheduledTask) -> {
                    for (Map.Entry<Location, Material> entry : originalBlocks.entrySet()) {
                        Location blockLoc = entry.getKey();
                        Material originalType = entry.getValue();
                        if (blockLoc.getBlock().getType() == Material.TNT) {
                            blockLoc.getBlock().setType(originalType);
                        }
                    }
                }, durationSeconds * 20L);
            } else {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    for (Map.Entry<Location, Material> entry : originalBlocks.entrySet()) {
                        Location blockLoc = entry.getKey();
                        Material originalType = entry.getValue();
                        if (blockLoc.getBlock().getType() == Material.TNT) {
                            blockLoc.getBlock().setType(originalType);
                        }
                    }
                }, durationSeconds * 20L);
            }
        }
    }

    public void handleRightClick(Location loc, Player player) {
        // 与 handleLeftClick 保持一致：先判空，避免后续 loc.clone() 触发 NPE
        if (loc == null || player == null) {
            return;
        }
        this.lastActor = player;

        // 等待创建者点击"启动游戏"期间，禁止插旗，提示玩家等待
        if (waitingForStart) {
            player.sendMessage(ChatColor.YELLOW + "请等待游戏开始");
            return;
        }

        if (!active || waitingForExit) {
            lastActivityTime = System.currentTimeMillis();
            // 如果在退出倒计时阶段点击，则重置倒计时
            if (waitingForExit) {
                exitCountdown = 10;
                showExitCountdown(player);
            }
            return;
        }

        lastActivityTime = System.currentTimeMillis();

        // 地图尚未生成（首次点击前）禁止插旗，避免混淆
        if (firstClick) {
            player.sendMessage(ChatColor.YELLOW + "请先左键翻开任意方块开始游戏，再右键插旗标记");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f);
            return;
        }

        loc = loc.clone();

        if (flaggedBlocks.contains(loc)) {
            removeFlag(loc);
            player.playSound(player.getLocation(), Sound.BLOCK_STONE_BUTTON_CLICK_OFF, 1.0f, 1.0f);
        } else if (flaggedBlocks.size() < customMineCount) {
            placeFlag(loc);
            player.playSound(player.getLocation(), Sound.BLOCK_STONE_BUTTON_CLICK_ON, 1.0f, 1.0f);
        } else {
            player.sendMessage(ChatColor.RED + "已达到最大标记数 (" + customMineCount + ")!");
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f);
        }
    }

    private void handleEndGameScenario(Player player) {
        lastActivityTime = System.currentTimeMillis();
        if (waitingForExit) {
            exitCountdown = 10;
            showExitCountdown(player);
            player.sendMessage(ChatColor.YELLOW + "游戏已结束，点击任意方块退出");
        }
    }

    public void handleFlagRemoval(Location torchLoc) {
        Location blockLoc = torchLoc.clone().add(0, -1, 0);
        if (platformBlocks.contains(blockLoc)) {
            removeFlag(blockLoc);
        }
    }

    private void placeFlag(Location loc) {
        if (!platformBlocks.contains(loc)) return;

        flaggedBlocks.add(loc);
        Location torchLoc = loc.clone().add(0, 1, 0);
        torchLoc.getBlock().setType(Material.REDSTONE_TORCH);
    }

    private void removeFlag(Location loc) {
        if (flaggedBlocks.remove(loc)) {
            Location torchLoc = loc.clone().add(0, 1, 0);
            if (torchLoc.getBlock().getType() == Material.REDSTONE_TORCH) {
                torchLoc.getBlock().setType(Material.AIR);
            }
        }
    }

    private boolean checkWin() {
        // 胜利条件：所有非雷方块都被翻开
        for (Location loc : platformBlocks) {
            // 如果这个方块是地雷，跳过检查
            if (isMine.getOrDefault(loc, false)) {
                continue;
            }
            
            // 如果这个方块没有被翻开，则尚未获胜
            if (!revealedBlocks.contains(loc)) {
                return false;
            }
        }
        
        // 所有非雷方块都已被翻开，游戏获胜
        return true;
    }

    public void endGame(boolean success) {
        // 防御性主线程检查：Paper/Spigot 上 World API 必须在主线程调用
        if (!MineSweeperPlugin.isFolia() && !Bukkit.isPrimaryThread()) {
            if (!platformBlocks.isEmpty()) {
                Location loc = platformBlocks.iterator().next();
                plugin.executeOnMainThread(loc, () -> endGame(success));
            }
            return;
        }
        
        if (!active) return;
        active = false;
        waitingForExit = false;
        plugin.removeGame(this);

        // 还原所有方块
        for (Location loc : platformBlocks) {
            loc.getBlock().setType(Material.GRAY_CONCRETE);
        }
        for (Location loc : flaggedBlocks) {
            Location torchLoc = loc.clone().add(0, 1, 0);
            if (torchLoc.getBlock().getType() == Material.REDSTONE_TORCH) {
                torchLoc.getBlock().setType(Material.AIR);
            }
        }
        flaggedBlocks.clear();
        revealedBlocks.clear();

        // 触发可编程奖励系统（具体行为由 config.yml 中 rewards.win / rewards.lose 决定）
        // 优先使用 lastActor（最近操作的玩家），若为 null 则回退到 creator（创建者）
        Player ctxPlayer = lastActor != null ? lastActor : creator;
        GameContext rewardContext = new GameContext(ctxPlayer, difficulty, platformBlocks.size(),
                customMineCount, autoFlagEnabled, platformBlocks);
        if (success) {
            plugin.getRewardManager().executeFlow("win", rewardContext);
        } else {
            plugin.getRewardManager().executeFlow("lose", rewardContext);
        }
    }

    // ===== 硬编码奖励逻辑已移除：奖励完全由 config.yml 的 rewards / logic 配置驱动 =====
    // 原 generateRewardChest / calculateRewardLevel / addWeightedRewards 等方法已删除，
    // 胜利/失败奖励改由 MineSweeperGame.endGame 调用 RewardManager.executeFlow 完成。

    // 显示退出倒计时给玩家
    private void showExitCountdown(Player player) {
        if (exitCountdown > 0) {
            player.sendTitle("", "游戏将在 " + exitCountdown + " 秒后自动退出", 0, 25, 5);
        } else if (exitCountdown == 0) {
            player.sendTitle("", "游戏退出", 0, 25, 5);
        }
    }

    // 向所有在平台方块上的玩家显示倒计时
    public void showCountdownToPlayers() {
        // 防御性主线程检查：Paper/Spigot 上 World API 必须在主线程调用
        // 若因调度错误等原因在异步线程执行，自动重定向到主线程，避免 AsyncCatcher 报错
        if (!MineSweeperPlugin.isFolia() && !Bukkit.isPrimaryThread()) {
            if (!platformBlocks.isEmpty()) {
                Location loc = platformBlocks.iterator().next();
                plugin.executeOnMainThread(loc, this::showCountdownToPlayers);
            }
            return;
        }
        
        if (exitCountdown > 0 && waitingForExit) {
            for (Location loc : platformBlocks) {
                // 获取在平台方块上方的玩家
                Location upLoc = loc.clone().add(0, 1, 0);
                Collection<Entity> entities = upLoc.getWorld().getNearbyEntities(upLoc, 0.5, 0.5, 0.5);
                for (Entity entity : entities) {
                    if (entity instanceof Player) {
                        Player player = (Player) entity;
                        showExitCountdown(player);
                    }
                }
            }
        }
    }

    // 获取退出倒计时
    public int getExitCountdown() {
        return exitCountdown;
    }

    // 减少退出倒计时
    public void decrementExitCountdown() {
        if (exitCountdown > 0) {
            exitCountdown--;
        }
    }

    // 创建教学GUI
    public void createTutorialGUI(Player player) {
        // 创建箱子GUI
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.GOLD + "扫雷教学与设置");

        // 添加数字颜色说明
        addItem(gui, 0, Material.WHITE_CONCRETE, "0 - 白色", "表示周围没有地雷");
        addItem(gui, 1, Material.LIGHT_BLUE_CONCRETE, "1 - 蓝色", "表示周围有1个地雷");
        addItem(gui, 2, Material.GREEN_CONCRETE, "2 - 绿色", "表示周围有2个地雷");
        addItem(gui, 3, Material.RED_CONCRETE, "3 - 红色", "表示周围有3个地雷");
        addItem(gui, 4, Material.PURPLE_CONCRETE, "4 - 紫色", "表示周围有4个地雷");
        addItem(gui, 5, Material.ORANGE_CONCRETE, "5 - 橙色", "表示周围有5个地雷");
        addItem(gui, 6, Material.LIME_CONCRETE, "6 - 黄绿色", "表示周围有6个地雷");
        addItem(gui, 7, Material.BROWN_CONCRETE, "7 - 棕色", "表示周围有7个地雷");
        addItem(gui, 8, Material.BLACK_CONCRETE, "8 - 黑色", "表示周围有8个地雷");

        // 自动标记开关
        List<String> autoFlagLore = new ArrayList<>();
        autoFlagLore.add("点击切换自动标记功能");
        
        // 检查自动标记是否可用
        if (isLargeEnoughForAutoFlag()) {
            autoFlagLore.add("开启后会自动标记明显的地雷位置");
            addItem(gui, 13, autoFlagEnabled ? Material.GREEN_CONCRETE : Material.RED_CONCRETE, 
                    autoFlagEnabled ? "自动标记: 开启" : "自动标记: 关闭", 
                    autoFlagLore.toArray(new String[0]));
        } else {
            // 场地太小时明确提示
            autoFlagLore.add(ChatColor.RED + "当前场地太小，自动标记不可用");
            autoFlagLore.add(ChatColor.RED + "需要至少10x10的场地才能使用此功能");
            addItem(gui, 13, Material.GRAY_CONCRETE, "自动标记: 不可用", autoFlagLore.toArray(new String[0]));
        }

        // 难度选择
        int platformSize = platformBlocks.size();
        int easyMineCount = Math.max(1, (int) (platformSize * 0.10));
        int mediumMineCount = Math.max(1, (int) (platformSize * 0.15));
        int hardMineCount = Math.max(1, (int) (platformSize * 0.20));

        addItem(gui, 18, Material.LIME_CONCRETE_POWDER, "简单难度", 
                "地雷数: " + easyMineCount, 
                "平台大小: " + platformSize,
                "点击选择简单难度");
        addItem(gui, 19, Material.YELLOW_CONCRETE_POWDER, "中等难度", 
                "地雷数: " + mediumMineCount, 
                "平台大小: " + platformSize,
                "点击选择中等难度");
        addItem(gui, 20, Material.RED_CONCRETE_POWDER, "困难难度", 
                "地雷数: " + hardMineCount, 
                "平台大小: " + platformSize,
                "点击选择困难难度");

        // 取消游戏按钮
        addItem(gui, 24, Material.BARRIER, "取消游戏",
                "点击取消本次游戏发起",
                "平台将被还原为灰色混凝土");

        // 启动游戏按钮 - 显示奖励预览
        addItem(gui, 26, Material.TNT, "启动游戏", 
                "难度: " + getDifficultyColor() + getDifficultyName(),
                "自动标记: " + getAutoFlagStatusText(),
                "预计奖励: " + ChatColor.GOLD + "由 config.yml 配置",
                "点击启动游戏");

        player.openInventory(gui);
    }

    // 添加物品到GUI
    private void addItem(Inventory inventory, int slot, Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null) {
                meta.setDisplayName(ChatColor.GOLD + name);
            }
            if (lore.length > 0) {
                List<String> loreList = new ArrayList<>();
                for (String line : lore) {
                    loreList.add(ChatColor.WHITE + line);
                }
                meta.setLore(loreList);
            }
            item.setItemMeta(meta);
        }
        inventory.setItem(slot, item);
    }

    // 设置自动标记开关
    public void toggleAutoFlag() {
        this.autoFlagEnabled = !this.autoFlagEnabled;
    }

    // 设置难度
    public void setDifficulty(int difficulty) {
        this.difficulty = difficulty;
        int platformSize = platformBlocks.size();
        switch (difficulty) {
            case 1: // 简单
                this.customMineCount = Math.max(1, (int) (platformSize * 0.10));
                break;
            case 2: // 中等
                this.customMineCount = Math.max(1, (int) (platformSize * 0.15));
                break;
            case 3: // 困难
                this.customMineCount = Math.max(1, (int) (platformSize * 0.20));
                break;
        }
    }

    // ===== 奖励预览方法已移除：奖励内容完全由 config.yml 决定，无需硬编码预览 =====

    // 获取难度名称
    public String getDifficultyName() {
        switch (difficulty) {
            case 1: return "简单";
            case 2: return "中等";
            case 3: return "困难";
            default: return "未知";
        }
    }

    // 获取难度颜色
    public ChatColor getDifficultyColor() {
        switch (difficulty) {
            case 1: return ChatColor.GREEN;
            case 2: return ChatColor.YELLOW;
            case 3: return ChatColor.RED;
            default: return ChatColor.WHITE;
        }
    }

    // 获取自动标记状态文本
    public String getAutoFlagStatusText() {
        if (!isLargeEnoughForAutoFlag()) {
            return ChatColor.GRAY + "不可用(场地太小)";
        }
        return autoFlagEnabled ? ChatColor.GREEN + "开启" : ChatColor.RED + "关闭";
    }

    // 线程安全的getter
    public boolean isActive() { return active; }
    public boolean isWaitingForExit() { return waitingForExit; }
    public long getLastActivityTime() { return lastActivityTime; }
    public Set<Location> getPlatformBlocks() { return platformBlocks; }
    public boolean isWaitingForStart() { return waitingForStart; }
    public long getWaitingSince() { return waitingSince; }
    public Player getCreator() { return creator; }

    // 创建者点击"启动游戏"：结束等待状态，游戏正式开始
    public void startGame() {
        this.waitingForStart = false;
        this.lastActivityTime = System.currentTimeMillis();
        notifyPlayersOnPlatform(ChatColor.GREEN + "游戏已开始！左键揭示，右键插旗");
    }

    /**
     * 创建者超时未启动游戏的兜底处理：直接解散游戏（还原平台、移除游戏），
     * 不触发任何胜负奖励。通过插件调度在主线程执行。
     */
    public void timeoutCancel() {
        // 防御性主线程检查：Paper/Spigot 上 World API 必须在主线程调用
        if (!MineSweeperPlugin.isFolia() && !Bukkit.isPrimaryThread()) {
            if (!platformBlocks.isEmpty()) {
                Location loc = platformBlocks.iterator().next();
                plugin.executeOnMainThread(loc, this::timeoutCancel);
            }
            return;
        }
        if (!active) return;
        active = false;
        waitingForStart = false;
        plugin.removeGame(this);

        for (Location loc : platformBlocks) {
            loc.getBlock().setType(Material.GRAY_CONCRETE);
        }
        for (Location loc : flaggedBlocks) {
            Location torchLoc = loc.clone().add(0, 1, 0);
            if (torchLoc.getBlock().getType() == Material.REDSTONE_TORCH) {
                torchLoc.getBlock().setType(Material.AIR);
            }
        }
        flaggedBlocks.clear();
        revealedBlocks.clear();

        notifyPlayersOnPlatform(ChatColor.RED + "创建者超时未启动游戏，本局已解散");
    }

    // 向平台上所有玩家发送消息
    private void notifyPlayersOnPlatform(String message) {
        for (Location loc : platformBlocks) {
            Location upLoc = loc.clone().add(0, 1, 0);
            Collection<Entity> entities = upLoc.getWorld().getNearbyEntities(upLoc, 0.5, 0.5, 0.5);
            for (Entity entity : entities) {
                if (entity instanceof Player) {
                    ((Player) entity).sendMessage(message);
                }
            }
        }
    }

    // 数字 → ChatColor 映射（与 setNumberBlock 的混凝土颜色一一对应）
    private ChatColor getNumberColor(int number) {
        switch (number) {
            case 0:  return ChatColor.WHITE;
            case 1:  return ChatColor.AQUA;        // 浅蓝
            case 2:  return ChatColor.DARK_GREEN;   // 绿色
            case 3:  return ChatColor.RED;          // 红色
            case 4:  return ChatColor.DARK_PURPLE;  // 紫色
            case 5:  return ChatColor.GOLD;         // 橙色
            case 6:  return ChatColor.GREEN;        // 黄绿色
            case 7:  return ChatColor.GRAY;         // 棕色 → 灰色替代
            case 8:  return ChatColor.DARK_GRAY;    // 黑色 → 深灰（纯黑不可读）
            default: return ChatColor.WHITE;
        }
    }

    /**
     * 每 tick 更新平台上所有玩家的 ActionBar。
     * - 视线指向旗帜(红石火把) → "左键拆除旗帜 | 雷数: xxx"
     * - 视线指向已翻开方块 → 同色显示 "此方块示数: n"
     * - 视线指向未翻开方块 → "手持任意物品右键插旗 | 雷数: xxx"
     * - 否则 → "雷数: xxx"
     */
    public void updatePlayerActionBars() {
        // 收集平台上方的所有玩家（去重）
        Set<Player> playersOnPlatform = new HashSet<>();
        for (Location loc : platformBlocks) {
            Location upLoc = loc.clone().add(0, 1, 0);
            Collection<Entity> entities = upLoc.getWorld().getNearbyEntities(upLoc, 0.5, 0.5, 0.5);
            for (Entity entity : entities) {
                if (entity instanceof Player) {
                    playersOnPlatform.add((Player) entity);
                }
            }
        }

        for (Player player : playersOnPlatform) {
            // 射线检测玩家视线指向的方块（5格内）
            Block target = player.getTargetBlockExact(5, FluidCollisionMode.NEVER);
            if (target != null) {
                Location targetLoc = target.getLocation();

                // 指向的是旗帜（红石火把在已标记方块上方）
                if (target.getType() == Material.REDSTONE_TORCH) {
                    Location below = targetLoc.clone().add(0, -1, 0);
                    if (platformBlocks.contains(below) && flaggedBlocks.contains(below)) {
                        player.sendActionBar(ChatColor.RED + "左键拆除旗帜" + ChatColor.GRAY + " | " + ChatColor.YELLOW + "雷数: " + customMineCount);
                        continue;
                    }
                }

                // 指向的是已翻开方块 → 显示数字
                if (platformBlocks.contains(targetLoc) && revealedBlocks.contains(targetLoc)) {
                    int number = numbers.getOrDefault(targetLoc, 0);
                    ChatColor color = getNumberColor(number);
                    player.sendActionBar(color + "此方块示数: " + number);
                    continue;
                }

                // 指向的是未翻开方块 → 提示插旗
                if (platformBlocks.contains(targetLoc) && !revealedBlocks.contains(targetLoc)) {
                    player.sendActionBar(ChatColor.GREEN + "手持任意物品右键插旗" + ChatColor.GRAY + " | " + ChatColor.YELLOW + "雷数: " + customMineCount);
                    continue;
                }
            }

            // 默认：视线未指向游戏内方块
            player.sendActionBar(ChatColor.YELLOW + "雷数: " + customMineCount);
        }
    }
}