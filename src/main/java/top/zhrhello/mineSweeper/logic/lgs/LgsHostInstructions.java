package top.zhrhello.mineSweeper.logic.lgs;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import top.zhrhello.mineSweeper.logic.Expression;
import top.zhrhello.mineSweeper.logic.GameContext;
import top.zhrhello.mineSweeper.logic.LogicException;
import top.zhrhello.mineSweeper.logic.Persistence;
import top.zhrhello.mineSweeper.logic.VaultHook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * LogicStep 宿主指令绑定实现。
 *
 * 将 LogicStep 标准指令集 + 本插件扩展指令映射到 Bukkit / Vault / Persistence API。
 *
 * <h3>指令分类</h3>
 * <ul>
 *   <li><b>游戏上下文</b>：get_player / get_difficulty / get_platform_size /
 *       get_mine_count / get_auto_flag</li>
 *   <li><b>经济</b>：give_money / eco_balance / eco_give / eco_take</li>
 *   <li><b>玩家交互</b>：send_message / check_permission / console_command /
 *       player_command / broadcast / play_sound / give_item</li>
 *   <li><b>随机</b>：random_int / random_chance</li>
 *   <li><b>列表</b>：list_create / list_add / list_get / list_set / list_remove /
 *       list_size / list_contains</li>
 *   <li><b>映射</b>：map_create / map_put / map_get / map_remove / map_keys</li>
 *   <li><b>字符串</b>：split / join</li>
 *   <li><b>数学</b>：floor / ceil / round / abs / max / min / pow / sqrt / clamp</li>
 *   <li><b>类型</b>：to_number / to_string / typeof</li>
 *   <li><b>日期</b>：now / date / date_diff / date_format / date_parse</li>
 *   <li><b>持久化</b>：store_set / store_get / store_remove</li>
 *   <li><b>日志</b>：log / notify_admin</li>
 * </ul>
 *
 * 数学/字符串/日期函数委托给 {@link Expression#callFunction} 复用已有实现。
 */
public final class LgsHostInstructions implements LgsHostBinding {

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final VaultHook vault;
    private final Persistence persistence;
    private final Map<String, Object> globals;
    private volatile Map<String, Object> constants;

    /** 所有已注册的指令名（用于 hasInstruction 静态检查）。 */
    private final Set<String> registeredNames;

    public LgsHostInstructions(org.bukkit.plugin.java.JavaPlugin plugin, VaultHook vault,
                               Persistence persistence, Map<String, Object> constants) {
        this.plugin = plugin;
        this.vault = vault;
        this.persistence = persistence;
        this.constants = constants;
        this.globals = new java.util.concurrent.ConcurrentHashMap<>();
        this.registeredNames = new LinkedHashSet<>();

        // 注册所有指令名
        registeredNames.addAll(Arrays.asList(
                // 游戏上下文
                "get_player", "get_difficulty", "get_platform_size", "get_mine_count", "get_auto_flag",
                // 经济
                "give_money", "eco_balance", "eco_give", "eco_take",
                // 玩家交互
                "send_message", "check_permission", "console_command", "player_command",
                "broadcast", "play_sound", "give_item",
                // 随机
                "random_int", "random_chance",
                // 列表
                "list_create", "list_add", "list_get", "list_set", "list_remove",
                "list_size", "list_contains",
                // 映射
                "map_create", "map_put", "map_get", "map_remove", "map_keys",
                // 字符串
                "split", "join",
                // 类型
                "to_number", "to_string", "typeof",
                // 持久化
                "store_set", "store_get", "store_remove",
                // 日志
                "log", "notify_admin"
        ));
        // 数学/日期函数由 Expression.callFunction 提供
        registeredNames.addAll(Arrays.asList(
                "floor", "ceil", "round", "abs", "max", "min", "pow", "sqrt", "clamp", "mod",
                "sign", "is_number", "is_string", "is_list", "is_map", "is_bool", "type_of",
                "to_int", "length", "upper", "lower", "trim", "index_of", "contains",
                "starts_with", "ends_with", "matches", "replace", "substr",
                "now", "date", "date_diff", "date_format", "date_parse",
                "if", "pick", "range"
        ));
    }

    // ==================== 指令分发 ====================

    @Override
    @SuppressWarnings("unchecked")
    public Object callInstruction(String name, List<Object> args, GameContext ctx) throws LogicException {
        switch (name) {
            // ==================== 游戏上下文 ====================
            case "get_player":
                return (ctx != null && ctx.player != null) ? ctx.player.getName() : "";
            case "get_difficulty":
                return (double) (ctx != null ? ctx.difficulty : 0);
            case "get_platform_size":
                return (double) (ctx != null ? ctx.platformSize : 0);
            case "get_mine_count":
                return (double) (ctx != null ? ctx.mineCount : 0);
            case "get_auto_flag":
                return (ctx != null && ctx.autoFlag) ? 1.0 : 0.0;

            // ==================== 经济 ====================
            case "give_money": {
                double amount = Expression.toDouble(args.get(0));
                Player p = (ctx != null) ? ctx.player : null;
                if (p != null) vault.deposit(p, amount);
                return null;
            }
            case "eco_balance": {
                Player p = (ctx != null) ? ctx.player : null;
                return vault.getBalance(p);
            }
            case "eco_give": {
                double amount = Expression.toDouble(args.get(0));
                Player p = resolvePlayer(args, 1, ctx);
                if (p != null) vault.deposit(p, amount);
                return null;
            }
            case "eco_take": {
                double amount = Expression.toDouble(args.get(0));
                Player p = resolvePlayer(args, 1, ctx);
                if (p != null) vault.withdraw(p, amount);
                return null;
            }

            // ==================== 玩家交互 ====================
            case "send_message": {
                String msg = colorize(Expression.toStr(args.get(0)));
                Player p = (ctx != null) ? ctx.player : null;
                if (p != null) p.sendMessage(msg);
                else plugin.getLogger().info("[LogicStep] send_message: " + msg);
                return null;
            }
            case "check_permission": {
                String perm = Expression.toStr(args.get(0));
                Player p = (ctx != null) ? ctx.player : null;
                return p != null && p.hasPermission(perm);
            }
            case "console_command": {
                String cmd = Expression.toStr(args.get(0));
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                return null;
            }
            case "player_command": {
                String cmd = Expression.toStr(args.get(0));
                Player p = (ctx != null) ? ctx.player : null;
                if (p != null) Bukkit.dispatchCommand(p, cmd);
                return null;
            }
            case "broadcast": {
                String msg = colorize(Expression.toStr(args.get(0)));
                Bukkit.broadcastMessage(msg);
                return null;
            }
            case "play_sound": {
                String soundName = Expression.toStr(args.get(0));
                Player p = (ctx != null) ? ctx.player : null;
                if (p != null) {
                    try {
                        Sound s = Sound.valueOf(soundName);
                        p.playSound(p.getLocation(), s, 1.0f, 1.0f);
                    } catch (Exception e) {
                        plugin.getLogger().warning("[LogicStep] 未知音效: " + soundName);
                    }
                }
                return null;
            }
            case "give_item": {
                String matName = Expression.toStr(args.get(0));
                int amount = args.size() >= 2 ? (int) Expression.toDouble(args.get(1)) : 1;
                Player p = (ctx != null) ? ctx.player : null;
                if (p != null) {
                    Material m = Material.matchMaterial(matName);
                    if (m != null) {
                        p.getInventory().addItem(new ItemStack(m, amount));
                    } else {
                        plugin.getLogger().warning("[LogicStep] 未知物品材质: " + matName);
                    }
                }
                return null;
            }

            // ==================== 随机 ====================
            case "random_int": {
                int min = (int) Expression.toDouble(args.get(0));
                int max = (int) Expression.toDouble(args.get(1));
                if (max < min) { int t = min; min = max; max = t; }
                return (double) (min + ThreadLocalRandom.current().nextInt(max - min + 1));
            }
            case "random_chance": {
                int pct = (int) Expression.toDouble(args.get(0));
                return ThreadLocalRandom.current().nextInt(100) < pct;
            }

            // ==================== 列表 ====================
            case "list_create":
                return new ArrayList<>();
            case "list_add": {
                Object list = args.get(0);
                if (!(list instanceof List)) throw new LogicException("[LogicStep] list_add: 第一个参数不是列表");
                ((List<Object>) list).add(args.get(1));
                return null;
            }
            case "list_get": {
                Object list = args.get(0);
                if (!(list instanceof List)) throw new LogicException("[LogicStep] list_get: 第一个参数不是列表");
                int idx = (int) Expression.toDouble(args.get(1));
                List<?> l = (List<?>) list;
                return (idx >= 0 && idx < l.size()) ? l.get(idx) : "";
            }
            case "list_set": {
                Object list = args.get(0);
                if (!(list instanceof List)) throw new LogicException("[LogicStep] list_set: 第一个参数不是列表");
                int idx = (int) Expression.toDouble(args.get(1));
                ((List<Object>) list).set(idx, args.get(2));
                return null;
            }
            case "list_remove": {
                Object list = args.get(0);
                if (list instanceof List) {
                    int idx = (int) Expression.toDouble(args.get(1));
                    ((List<?>) list).remove(idx);
                }
                return null;
            }
            case "list_size": {
                Object list = args.get(0);
                return (double) ((list instanceof List) ? ((List<?>) list).size() : 0);
            }
            case "list_contains": {
                Object list = args.get(0);
                if (!(list instanceof List)) throw new LogicException("[LogicStep] list_contains: 第一个参数不是列表");
                Object val = args.get(1);
                for (Object item : (List<?>) list) {
                    if (Expression.toStr(item).equals(Expression.toStr(val))) return true;
                }
                return false;
            }

            // ==================== 映射 ====================
            case "map_create":
                return new LinkedHashMap<String, Object>();
            case "map_put": {
                Object m = args.get(0);
                if (!(m instanceof Map)) throw new LogicException("[LogicStep] map_put: 第一个参数不是映射");
                ((Map<String, Object>) m).put(Expression.toStr(args.get(1)), args.get(2));
                return null;
            }
            case "map_get": {
                Object m = args.get(0);
                if (m instanceof Map) {
                    Object v = ((Map<String, Object>) m).get(Expression.toStr(args.get(1)));
                    return v != null ? v : "";
                }
                return "";
            }
            case "map_remove": {
                Object m = args.get(0);
                if (m instanceof Map) ((Map<String, Object>) m).remove(Expression.toStr(args.get(1)));
                return null;
            }
            case "map_keys": {
                Object m = args.get(0);
                List<Object> keys = new ArrayList<>();
                if (m instanceof Map) keys.addAll(((Map<String, Object>) m).keySet());
                return keys;
            }

            // ==================== 字符串 ====================
            case "to_string":
                return Expression.toStr(args.get(0));

            // ==================== 类型 ====================
            case "typeof":
                return typeOf(args.get(0));

            // ==================== 持久化 ====================
            case "store_set": {
                String key = Expression.toStr(args.get(0));
                Object val = args.get(1);
                persistence.set(key, val);
                return null;
            }
            case "store_get": {
                String key = Expression.toStr(args.get(0));
                Object v = persistence.get(key);
                return v;
            }
            case "store_remove": {
                String key = Expression.toStr(args.get(0));
                persistence.remove(key);
                return null;
            }

            // ==================== 日志 ====================
            case "log": {
                String levelStr = args.size() >= 2 ? Expression.toStr(args.get(0)).toUpperCase() : "INFO";
                String msg = args.size() >= 2 ? Expression.toStr(args.get(1)) : Expression.toStr(args.get(0));
                Level level;
                try { level = Level.parse(levelStr); }
                catch (Exception e) { level = Level.INFO; }
                plugin.getLogger().log(level, "[LogicStep] " + msg);
                return null;
            }
            case "notify_admin": {
                String msg = colorize(Expression.toStr(args.get(0)));
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission("minesweeper.admin")) p.sendMessage(msg);
                }
                plugin.getLogger().info("[LogicStep] " + msg);
                return null;
            }

            // ==================== 委托给 Expression 的函数 ====================
            default:
                // split / join / floor / ceil / round / abs / max / min / pow / sqrt / clamp / mod / sign
                // is_number / is_string / ... / type_of / to_number / to_int
                // length / upper / lower / trim / index_of / contains / starts_with / ends_with / matches / replace / substr
                // now / date / date_diff / date_format / date_parse
                // if / pick / range
                try {
                    return Expression.callFunction(name, args);
                } catch (LogicException e) {
                    throw new LogicException("[LogicStep] 未知指令或函数: " + name);
                }
        }
    }

    @Override
    public boolean hasInstruction(String name) {
        return registeredNames.contains(name);
    }

    @Override
    public List<String> instructionNames() {
        return new ArrayList<>(registeredNames);
    }

    @Override
    public Map<String, Object> getGlobals() {
        return globals;
    }

    @Override
    public Map<String, Object> getConstants() {
        return constants;
    }

    /** 更新常量引用（reload 后常量可能变化）。 */
    public void updateConstants(Map<String, Object> newConstants) {
        this.constants = newConstants;
    }

    // ==================== 辅助方法 ====================

    private Player resolvePlayer(List<Object> args, int idx, GameContext ctx) {
        if (idx < args.size()) {
            String name = Expression.toStr(args.get(idx));
            if (!name.isEmpty()) return Bukkit.getPlayerExact(name);
        }
        return (ctx != null) ? ctx.player : null;
    }

    private static String colorize(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private static String typeOf(Object o) {
        if (o == null) return "null";
        if (o instanceof Number) return "number";
        if (o instanceof String) return "string";
        if (o instanceof Boolean) return "boolean";
        if (o instanceof List) return "list";
        if (o instanceof Map) return "map";
        return "unknown";
    }
}
