package top.zhrhello.mineSweeper.rewards;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import top.zhrhello.mineSweeper.MineSweeperPlugin;
import top.zhrhello.mineSweeper.config.ConfigManager;
import top.zhrhello.mineSweeper.config.RewardAction;
import top.zhrhello.mineSweeper.config.RewardFlow;
import top.zhrhello.mineSweeper.folia.SchedulerCompat;
import top.zhrhello.mineSweeper.logic.Expression;
import top.zhrhello.mineSweeper.logic.GameContext;
import top.zhrhello.mineSweeper.logic.LogicEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 奖励执行器：负责执行 rewards.win / rewards.lose 中的流程。
 *
 * 职责：
 * - 流程触发判定（trigger 函数返回真才执行）
 * - 动作组内变量绑定（vars），保证函数仅执行一次并缓存
 * - 占位符解析（内置上下文变量 → vars → global 常量 → 弃用的 logic: 占位符）
 * - 八种动作类型执行（命令 / 消息 / 广播 / 音效 / 物品 / 箱子 / 延迟）
 * - give_chest 调用 Logic 函数并以返回值（列表/映射）填充箱子
 */
public class RewardManager {

    private static final Pattern PLACEHOLDER_ANY = Pattern.compile("\\{([^{}]+)\\}");

    private final MineSweeperPlugin plugin;
    private final ConfigManager config;
    private final LogicEngine engine;

    public RewardManager(MineSweeperPlugin plugin, ConfigManager config, LogicEngine engine) {
        this.plugin = plugin;
        this.config = config;
        this.engine = engine;
    }

    // ===================== 流程执行 =====================

    public void executeFlow(String flowName, GameContext gameCtx) {
        // 整个奖励流程在玩家所在 region 线程执行（Folia），保证后续 play_sound / give_item /
        // player_command / give_chest 等动作的玩家/世界访问都在正确线程。
        // 无玩家上下文时回退全局区域线程（仅适合无世界访问的动作）。
        Player p = (gameCtx != null) ? gameCtx.player : null;
        if (p != null) {
            SchedulerCompat.runOnEntity(plugin, p, () -> executeFlowInternal(flowName, gameCtx));
        } else {
            SchedulerCompat.runOnGlobal(plugin, () -> executeFlowInternal(flowName, gameCtx));
        }
    }

    private void executeFlowInternal(String flowName, GameContext gameCtx) {
        List<RewardFlow> flows = config.getRewardFlows(flowName);
        if (flows == null || flows.isEmpty()) return;

        for (RewardFlow flow : flows) {
            // 触发判定
            if (flow.trigger != null && !flow.trigger.isEmpty()) {
                String fname = stripLogicPrefix(flow.trigger);
                Object r = engine.execute(fname, new ArrayList<>(), gameCtx);
                if (!Expression.isTruthy(r)) continue; // 跳过该流程
            }
            // 变量绑定
            Map<String, Object> bound = new LinkedHashMap<>();
            if (flow.vars != null) {
                for (Map.Entry<String, String> e : flow.vars.entrySet()) {
                    String spec = e.getValue();
                    if (spec.startsWith("logic:")) {
                        String fname = stripLogicPrefix(spec);
                        Object rv = engine.execute(fname, new ArrayList<>(), gameCtx);
                        bound.put(e.getKey(), rv);
                    } else {
                        bound.put(e.getKey(), spec);
                    }
                }
            }
            ActionContext ac = new ActionContext(plugin, engine, config, gameCtx, bound);
            runActions(flow.actions, ac, 0);
        }
    }

    private static String stripLogicPrefix(String s) {
        String fname = s.startsWith("logic:") ? s.substring("logic:".length()) : s;
        int colon = fname.indexOf(':');
        if (colon >= 0) fname = fname.substring(0, colon); // 兼容 "func:param" 写法
        return fname;
    }

    // ===================== 动作序列（支持 delay 调度） =====================

    private void runActions(List<RewardAction> actions, ActionContext ac, int startIndex) {
        for (int i = startIndex; i < actions.size(); i++) {
            RewardAction action = actions.get(i);
            if (action.type.equals("delay")) {
                int ticks = parseAmount(action.params.get("ticks"));
                if (ticks > 0) {
                    final int next = i + 1;
                    Player p = (ac.gameCtx != null) ? ac.gameCtx.player : null;
                    Runnable resume = () -> runActions(actions, ac, next);
                    // 延迟后在玩家区域线程继续（保证后续玩家/世界访问线程正确）；
                    // 无玩家时用异步线程（后续动作中的世界访问需自行通过 runOnRegionOwned 路由）
                    if (p != null) {
                        SchedulerCompat.runOnEntityDelayed(plugin, p, resume, ticks);
                    } else {
                        SchedulerCompat.runAsyncDelayed(plugin, resume, ticks);
                    }
                    return;
                }
                continue;
            }
            executeAction(action, ac);
        }
    }

    // ===================== 单动作执行 =====================

    private void executeAction(RewardAction action, ActionContext ac) {
        GameContext g = ac.gameCtx;
        Player player = (g != null) ? g.player : null;
        switch (action.type) {
            case "console_command": {
                String cmd = substitute(str(action.params.get("command")), ac);
                SchedulerCompat.dispatchCommand(plugin, Bukkit.getConsoleSender(), cmd);
                break;
            }
            case "player_command": {
                String cmd = substitute(str(action.params.get("command")), ac);
                if (player != null) {
                    SchedulerCompat.dispatchCommand(plugin, player, cmd);
                } else {
                    plugin.getLogger().warning("[Reward] player_command 缺少玩家上下文，已跳过");
                }
                break;
            }
            case "message": {
                String msg = colorize(substitute(str(action.params.get("message")), ac));
                if (player != null) player.sendMessage(msg);
                else plugin.getLogger().info("[Reward] message: " + msg);
                break;
            }
            case "broadcast": {
                String msg = colorize(substitute(str(action.params.get("message")), ac));
                Bukkit.broadcastMessage(msg);
                break;
            }
            case "sound": {
                String name = substitute(str(action.params.get("sound")), ac);
                if (player != null) {
                    try {
                        Sound s = Sound.valueOf(name);
                        player.playSound(player.getLocation(), s, 1.0f, 1.0f);
                    } catch (Exception e) {
                        plugin.getLogger().warning("[Reward] 未知音效: " + name);
                    }
                }
                break;
            }
            case "give_item": {
                String matName = substitute(str(action.params.get("material")), ac);
                int amount = parseAmount(action.params.get("amount"));
                if (player != null) addItem(player.getInventory(), matName, amount, ac);
                else plugin.getLogger().warning("[Reward] give_item 缺少玩家上下文，已跳过");
                break;
            }
            case "give_chest": {
                String func = substitute(str(action.params.get("use_logic")), ac);
                executeGiveChest(func, ac);
                break;
            }
            default:
                plugin.getLogger().warning("[Reward] 未支持的动作类型: " + action.type);
        }
    }

    // ===================== give_chest 箱子填充 =====================

    private void executeGiveChest(String funcName, ActionContext ac) {
        GameContext g = ac.gameCtx;
        if (g == null || g.platformBlocks == null || g.platformBlocks.isEmpty()) {
            ac.plugin.getLogger().warning("[Reward] give_chest 缺少平台信息，已跳过");
            return;
        }

        // 只执行一次 Logic 函数，把战利品拆成叠堆列表（每叠不超过材质最大堆叠数）
        Object ret = ac.engine.execute(funcName, new ArrayList<>(), g);
        List<ItemStack> stacks = buildLootStacks(ret, ac);
        if (stacks.isEmpty()) {
            return; // 没有战利品，不放置空箱子
        }

        // 每个箱子最多 27 叠，按 27 一批拆分；战利品过多时自动放置多个箱子
        List<List<ItemStack>> batches = new ArrayList<>();
        for (int i = 0; i < stacks.size(); i += 27) {
            batches.add(new ArrayList<>(stacks.subList(i, Math.min(i + 27, stacks.size()))));
        }

        // 随机洗牌平台方块作为候选位置：箱子每次落在完全随机的位置
        List<Location> candidates = new ArrayList<>(g.platformBlocks);
        Collections.shuffle(candidates, ThreadLocalRandom.current());

        int need = batches.size();
        AtomicInteger placedCount = new AtomicInteger(0);

        // 遍历随机候选位置，在各自 region 线程检查空气并放置箱子。
        // 用原子计数保证恰好放置 need 个箱子；平台跨 region 时并发安全。
        for (Location loc : candidates) {
            if (placedCount.get() >= need) break;
            Location up = loc.clone().add(0, 1, 0);
            SchedulerCompat.runOnRegionOwned(ac.plugin, up, () -> {
                if (up.getBlock().getType() != Material.AIR) return;
                int idx = placedCount.getAndIncrement();
                if (idx >= need) return;
                up.getBlock().setType(Material.CHEST);
                Chest chest = (Chest) up.getBlock().getState();
                Inventory inv = chest.getInventory();
                for (ItemStack stack : batches.get(idx)) {
                    inv.addItem(stack);
                }
            });
        }
    }

    /**
     * 把 Logic 函数返回值解析为战利品叠堆列表（每叠不超过材质最大堆叠数）。
     * 支持返回值类型：列表（元素为 "材质:数量"）、映射（材质 → 数量）、单个 "材质:数量" 字符串。
     */
    private List<ItemStack> buildLootStacks(Object ret, ActionContext ac) {
        List<ItemStack> stacks = new ArrayList<>();
        if (ret instanceof List) {
            for (Object o : (List<?>) ret) {
                addStackFromString(stacks, Expression.toStr(o), ac);
            }
        } else if (ret instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) ret).entrySet()) {
                String mat = Expression.toStr(e.getKey());
                int amt = (int) Math.floor(Expression.toDouble(e.getValue()));
                addStack(stacks, mat, amt, ac);
            }
        } else if (ret instanceof String) {
            addStackFromString(stacks, (String) ret, ac);
        } else {
            ac.plugin.getLogger().warning("[Reward] give_chest 函数返回值不是列表/映射/字符串，已跳过填充");
        }
        return stacks;
    }

    private void addStackFromString(List<ItemStack> stacks, String s, ActionContext ac) {
        if (s == null || s.isEmpty()) return;
        String[] parts = s.split(":");
        String mat = parts[0].trim();
        int amt = 1;
        if (parts.length >= 2) {
            try {
                amt = Integer.parseInt(parts[1].trim());
            } catch (Exception e) {
                amt = 1;
            }
        }
        addStack(stacks, mat, amt, ac);
    }

    private void addStack(List<ItemStack> stacks, String matName, int amt, ActionContext ac) {
        if (amt <= 0) return;
        Material m = Material.matchMaterial(matName);
        if (m == null) {
            ac.plugin.getLogger().warning("[Reward] 未知物品材质: " + matName);
            return;
        }
        int max = m.getMaxStackSize();
        while (amt > 0) {
            int cur = Math.min(amt, max);
            stacks.add(new ItemStack(m, cur));
            amt -= cur;
        }
    }

    private void addItem(Inventory inv, String matName, int amt, ActionContext ac) {
        if (amt <= 0) return;
        Material m = Material.matchMaterial(matName);
        if (m == null) {
            ac.plugin.getLogger().warning("[Reward] 未知物品材质: " + matName);
            return;
        }
        inv.addItem(new ItemStack(m, amt));
    }

    // ===================== 占位符 / 工具 =====================

    private String substitute(String template, ActionContext ac) {
        if (template == null) return "";
        Matcher m = PLACEHOLDER_ANY.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String content = m.group(1).trim();
            String replacement = resolveActionPlaceholder(content, ac);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String resolveActionPlaceholder(String content, ActionContext ac) {
        GameContext g = ac.gameCtx;
        if (content.equals("player")) {
            return (g != null && g.player != null) ? g.player.getName() : "";
        }
        if (content.equals("difficulty")) return g != null ? String.valueOf(g.difficulty) : "";
        if (content.equals("platform_size")) return g != null ? String.valueOf(g.platformSize) : "";
        if (content.equals("mine_count")) return g != null ? String.valueOf(g.mineCount) : "";
        if (content.startsWith("global.")) {
            String key = content.substring("global.".length());
            Object v = ac.config.getConstant(key);
            if (v == null) v = ac.engine.getGlobal(content);
            return Expression.toStr(v);
        }
        if (content.startsWith("logic:")) {
            String fname = stripLogicPrefix(content);
            ac.plugin.getLogger().warning("[Reward] 检测到已弃用的 {logic:" + fname
                    + "} 占位符，建议改用 rewards.vars 绑定以避免重复求值。");
            Object r = ac.engine.execute(fname, new ArrayList<>(), g);
            return Expression.toStr(r);
        }
        if (ac.vars != null && ac.vars.containsKey(content)) {
            return Expression.toStr(ac.vars.get(content));
        }
        return ""; // 未知占位符置空
    }

    private String colorize(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private int parseAmount(Object o) {
        if (o == null) return 1;
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return 1;
        }
    }
}
