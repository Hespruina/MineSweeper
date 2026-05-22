package top.zhrhello.mineSweeper;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
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

    public MineSweeperGame(MineSweeperPlugin plugin, Set<Location> platformBlocks, Location startLocation) {
        this.plugin = plugin;
        this.platformBlocks = Collections.unmodifiableSet(new HashSet<>(platformBlocks));
        this.startLocation = startLocation.clone();
        this.isMine = new HashMap<>();
        this.numbers = new HashMap<>();
        this.flaggedBlocks = new HashSet<>();
        this.revealedBlocks = new HashSet<>();
        this.lastActivityTime = System.currentTimeMillis();
        this.active = true;
        this.waitingForExit = false;

        // 计算雷数 (15% of platform size, min 1)
        int totalBlocks = platformBlocks.size();
        this.mineCount = Math.max(1, (int) (totalBlocks * 0.15));
        this.customMineCount = this.mineCount;

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

    // 首次点击时放置地雷，确保首次点击位置及其周围不放置地雷
    private void placeMines(Location firstClickLoc) {
        // 排除起始位置及其周围位置
        List<Location> safeBlocks = new ArrayList<>(platformBlocks);
        safeBlocks.remove(firstClickLoc);
        
        // 移除首次点击位置周围的方块
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Location neighbor = firstClickLoc.clone().add(dx, 0, dz);
                safeBlocks.remove(neighbor);
            }
        }
        
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
        player.playSound(player.getLocation(), Sound.BLOCK_STONE_BUTTON_CLICK_OFF, 1.0f, 1.0f);

        // 检查胜利
        if (checkWin()) {
            endGame(true);
            player.sendMessage(ChatColor.GOLD + "扫雷成功！奖励箱已生成");
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
        
        // 自动标记功能 (根据开关决定是否启用)
        // 只有当游戏区域大于等于10*10时才启用自动标记
        if (autoFlagEnabled && isLargeEnoughForAutoFlag()) {
            autoFlagMines();
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
        
        // 创建副本以避免ConcurrentModificationException
        Set<Location> revealedBlocksCopy = new HashSet<>(revealedBlocks);
        
        // 削弱自动标记功能 - 只处理确定性标记（只当未标记邻居数等于剩余雷数时才标记）
        // 遍历所有已翻开的数字格子
        for (Location loc : revealedBlocksCopy) {
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
        
        // 如果有新的标记被放置，检查是否可以触发连锁翻开
        if (changed) {
            // 创建副本以避免ConcurrentModificationException
            Set<Location> revealedBlocksCopy2 = new HashSet<>(revealedBlocks);
            
            // 遍历所有已标记的方块，检查是否可以触发连锁翻开
            for (Location loc : revealedBlocksCopy2) {
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
            player.sendMessage(ChatColor.GOLD + "扫雷成功！奖励箱已生成");
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
        
        // 延迟恢复原来的方块类型
        try {
            // 尝试使用Folia的区域调度器
            getClass().getClassLoader().loadClass("io.papermc.paper.threadedregions.RegionizedServer");
            // 使用Folia调度器
            if (!platformBlocks.isEmpty()) {
                Location loc = platformBlocks.iterator().next();
                plugin.getServer().getRegionScheduler().runDelayed(plugin, loc, (scheduledTask) -> {
                    for (Map.Entry<Location, Material> entry : originalBlocks.entrySet()) {
                        Location blockLoc = entry.getKey();
                        Material originalType = entry.getValue();
                        if (blockLoc.getBlock().getType() == Material.TNT) {
                            blockLoc.getBlock().setType(originalType);
                        }
                    }
                }, durationSeconds * 20L);
            }
        } catch (ClassNotFoundException e) {
            // 回退到传统的Bukkit调度器
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                for (Map.Entry<Location, Material> entry : originalBlocks.entrySet()) {
                    Location loc = entry.getKey();
                    Material originalType = entry.getValue();
                    if (loc.getBlock().getType() == Material.TNT) {
                        loc.getBlock().setType(originalType);
                    }
                }
            }, durationSeconds * 20L); // durationSeconds秒后恢复
        }
    }

    public void handleRightClick(Location loc, Player player) {
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

        // 生成奖励
        if (success) {
            generateRewardChest();
        }
    }

    private void generateRewardChest() {
        // 找一个安全位置 (上方为空气)
        List<Location> validSpots = new ArrayList<>();
        for (Location loc : platformBlocks) {
            Location chestLoc = loc.clone().add(0, 1, 0);
            if (chestLoc.getBlock().getType() == Material.AIR) {
                validSpots.add(chestLoc);
            }
        }

        if (validSpots.isEmpty()) return;

        // 生成箱子
        Location chestLoc = validSpots.get(ThreadLocalRandom.current().nextInt(validSpots.size()));
        chestLoc.getBlock().setType(Material.CHEST);

        // 填充奖励
        Chest chest = (Chest) chestLoc.getBlock().getState();
        Inventory inv = chest.getInventory();
        
        // 基础奖励 - 始终给予TNT
        inv.addItem(new ItemStack(Material.TNT, 1));
        
        // 计算奖励等级
        int rewardLevel = calculateRewardLevel();
        
        // 根据奖励等级和随机权重添加额外奖励
        addWeightedRewards(inv, rewardLevel);
    }

    // 计算奖励等级
    private int calculateRewardLevel() {
        int rewardLevel = 0;
        int platformSize = platformBlocks.size();
        
        // 难度奖励
        switch (difficulty) {
            case 1: rewardLevel = 1; break; // 简单
            case 2: rewardLevel = 2; break; // 中等
            case 3: rewardLevel = 3; break; // 困难
        }
        
        // 自动标记惩罚（减少奖励）
        if (autoFlagEnabled) {
            rewardLevel = Math.max(0, rewardLevel - 1);
        }
        
        // 平台大小奖励
        if (platformSize > 100) {
            rewardLevel += 1;
        } else if (platformSize > 50) {
            rewardLevel += 0;
        } else {
            rewardLevel -= 1;
        }
        
        return rewardLevel;
    }
    
    // 基于权重和奖励等级添加奖励
    private void addWeightedRewards(Inventory inventory, int rewardLevel) {
        // 定义奖励权重 - 每个奖励项包含物品类型和权重数组（对应不同奖励等级）
        Map<Material, int[]> rewardWeights = new HashMap<>();
        rewardWeights.put(getRandomConcrete(), new int[]{20, 40, 60, 80}); // 混凝土
        rewardWeights.put(Material.IRON_INGOT, new int[]{0, 30, 50, 70}); // 铁锭
        rewardWeights.put(Material.GOLD_INGOT, new int[]{0, 0, 20, 40}); // 金锭
        rewardWeights.put(Material.DIAMOND, new int[]{0, 0, 5, 15}); // 钻石
        
        // 遍历奖励表
        for (Map.Entry<Material, int[]> entry : rewardWeights.entrySet()) {
            Material material = entry.getKey();
            int[] weights = entry.getValue();
            
            // 确保奖励等级在有效范围内
            if (rewardLevel >= 0 && rewardLevel < weights.length) {
                int weight = weights[rewardLevel];
                
                // 根据权重决定是否添加奖励
                if (ThreadLocalRandom.current().nextInt(100) < weight) {
                    // 根据难度和权重确定数量
                    int amount = calculateRewardAmount(material, rewardLevel);
                    if (amount > 0) {
                        inventory.addItem(new ItemStack(material, amount));
                    }
                }
            }
        }
    }
    
    // 根据物品类型、奖励等级计算奖励数量
    private int calculateRewardAmount(Material material, int rewardLevel) {
        // 基础数量基于奖励等级
        int baseAmount = 0;
        switch (rewardLevel) {
            case 1: baseAmount = ThreadLocalRandom.current().nextInt(1, 3); break;
            case 2: baseAmount = ThreadLocalRandom.current().nextInt(1, 4); break;
            case 3: baseAmount = ThreadLocalRandom.current().nextInt(2, 5); break;
            case 4: baseAmount = ThreadLocalRandom.current().nextInt(2, 6); break;
            default: baseAmount = 1;
        }
        
        // 根据物品类型调整数量
        if (material == Material.DIAMOND) {
            // 钻石奖励较少
            return ThreadLocalRandom.current().nextInt(1, Math.max(2, baseAmount));
        } else if (material == Material.GOLD_INGOT) {
            // 金锭适中
            return ThreadLocalRandom.current().nextInt(1, baseAmount + 1);
        } else if (material == Material.IRON_INGOT) {
            // 铁锭较多
            return ThreadLocalRandom.current().nextInt(1, baseAmount + 2);
        } else {
            // 混凝土等其他材料最多
            return ThreadLocalRandom.current().nextInt(1, baseAmount + 3);
        }
    }

    private Material getRandomConcrete() {
        Material[] concretes = {
                Material.WHITE_CONCRETE, Material.ORANGE_CONCRETE, Material.MAGENTA_CONCRETE,
                Material.LIGHT_BLUE_CONCRETE, Material.YELLOW_CONCRETE, Material.LIME_CONCRETE,
                Material.PINK_CONCRETE, Material.GRAY_CONCRETE, Material.LIGHT_GRAY_CONCRETE,
                Material.CYAN_CONCRETE, Material.PURPLE_CONCRETE, Material.BLUE_CONCRETE,
                Material.BROWN_CONCRETE, Material.GREEN_CONCRETE, Material.RED_CONCRETE, Material.BLACK_CONCRETE
        };
        return concretes[ThreadLocalRandom.current().nextInt(concretes.length)];
    }

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

        // 启动游戏按钮 - 显示奖励预览
        addItem(gui, 26, Material.TNT, "启动游戏", 
                "难度: " + getDifficultyColor() + getDifficultyName(),
                "自动标记: " + getAutoFlagStatusText(),
                "预计奖励: " + ChatColor.GOLD + calculateRewardPreview(),
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

    // 计算奖励预览
    public String calculateRewardPreview() {
        StringBuilder preview = new StringBuilder();
        
        // 基础奖励
        preview.append("基础奖励: 1xTNT");
        
        // 计算奖励等级
        int rewardLevel = calculateRewardLevel();
        
        // 添加可能的奖励预览
        if (rewardLevel >= 1) {
            preview.append(", 混凝土(权重: ").append(getRewardWeight(getRandomConcrete(), rewardLevel)).append("%)");
        }
        
        if (rewardLevel >= 2) {
            preview.append(", 铁锭(权重: ").append(getRewardWeight(Material.IRON_INGOT, rewardLevel)).append("%)");
        }
        
        if (rewardLevel >= 3) {
            preview.append(", 金锭(权重: ").append(getRewardWeight(Material.GOLD_INGOT, rewardLevel)).append("%)");
        }
        
        if (rewardLevel >= 4) {
            preview.append(", 钻石(权重: ").append(getRewardWeight(Material.DIAMOND, rewardLevel)).append("%)");
        }
        
        return preview.toString();
    }

    // 获取指定物品和奖励等级的权重
    private int getRewardWeight(Material material, int rewardLevel) {
        Map<Material, int[]> rewardWeights = new HashMap<>();
        rewardWeights.put(getRandomConcrete(), new int[]{20, 40, 60, 80}); // 混凝土
        rewardWeights.put(Material.IRON_INGOT, new int[]{0, 30, 50, 70}); // 铁锭
        rewardWeights.put(Material.GOLD_INGOT, new int[]{0, 0, 20, 40}); // 金锭
        rewardWeights.put(Material.DIAMOND, new int[]{0, 0, 5, 15}); // 钻石

        for (Map.Entry<Material, int[]> entry : rewardWeights.entrySet()) {
            if (entry.getKey() == material && rewardLevel >= 0 && rewardLevel < entry.getValue().length) {
                return entry.getValue()[rewardLevel];
            }
        }

        return 0;
    }

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
}