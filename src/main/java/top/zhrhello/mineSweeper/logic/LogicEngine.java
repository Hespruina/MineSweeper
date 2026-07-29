package top.zhrhello.mineSweeper.logic;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import top.zhrhello.mineSweeper.config.ConfigManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logic 逻辑编程引擎 —— 基于 YAML 步骤序列的解释器。
 *
 * 特性：
 * - 完整步骤类型（赋值 / 表达式 / 条件 / 循环 / 列表 / 映射 / 函数调用 / 持久化 ...）
 * - 显式作用域（局部、参数、全局、动作组），全局变量跨调用持久
 * - 上下文自动继承（call 默认继承玩家与游戏信息）
 * - 安全限制：总步数上限、循环最大迭代、递归深度、表达式 5ms 超时
 * - 统一返回值模型（return 显式返回，否则空串）
 */
public class LogicEngine {

    // 全局变量表（"global." 前缀），跨函数、跨触发调用持久存在（重启丢失）。
    private final Map<String, Object> globalVars = new ConcurrentHashMap<>();
    private final ConfigManager config;
    private final Persistence persistence;
    private final JavaPlugin plugin;
    private final VaultHook vault;

    private static final Pattern PLACEHOLDER_ANY = Pattern.compile("\\{([^{}]+)\\}");
    private static final Pattern PLACEHOLDER_FULL = Pattern.compile("^\\{([^{}]+)\\}$");
    private static final Pattern VAR_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");

    private enum Signal { CONTINUE, RETURN, BREAK }

    public LogicEngine(ConfigManager config, Persistence persistence, JavaPlugin plugin) {
        this.config = config;
        this.persistence = persistence;
        this.plugin = plugin;
        this.vault = new VaultHook(plugin);
    }

    public Map<String, Object> getGlobals() {
        return globalVars;
    }

    public Object getGlobal(String name) {
        return globalVars.get(name);
    }

    // ===================== 顶层入口 =====================

    /** 执行一个顶层 Logic 函数。捕获运行时异常并做错误上报，返回函数结果（失败返回 ""）。 */
    public Object execute(String functionName, List<Object> args, GameContext gameCtx) {
        LogicFunction func = config.getFunction(functionName);
        if (func == null) {
            plugin.getLogger().warning("[Logic] 未找到函数: " + functionName);
            return "";
        }
        EvaluationContext ctx = new EvaluationContext(globalVars, config.getConstants(), persistence, plugin, config.getMaxSteps());
        try {
            return executeFunction(func, args == null ? new ArrayList<>() : args, ctx, gameCtx);
        } catch (LogicException e) {
            plugin.getLogger().log(Level.SEVERE, "[Logic] 执行函数 '" + functionName + "' 出错: " + e.getMessage());
            if (gameCtx != null && gameCtx.player != null) {
                notifyAdmins("§c[扫雷] Logic 脚本执行出错: " + e.getMessage());
                gameCtx.player.sendMessage("§c很抱歉，奖励脚本执行时出现问题，已通知管理员。");
            }
            return "";
        }
    }

    private Object executeFunction(LogicFunction func, List<Object> args, EvaluationContext ctx, GameContext eff)
            throws LogicException {
        if (ctx.depth() >= 50) {
            throw new LogicException("递归过深（超过 50 层）");
        }
        Map<String, Object> scope = new LinkedHashMap<>();
        if (func.parameters != null) {
            for (int i = 0; i < func.parameters.size(); i++) {
                String pname = func.parameters.get(i);
                Object val = (i < args.size()) ? args.get(i) : null;
                scope.put(pname, val);
            }
        }
        ctx.pushScope(scope);
        try {
            Signal sig = execSteps(func.steps, ctx, eff);
            return (sig == Signal.RETURN) ? ctx.returnValue : "";
        } finally {
            ctx.popScope();
        }
    }

    private Signal execSteps(List<Step> steps, EvaluationContext ctx, GameContext eff) throws LogicException {
        for (Step step : steps) {
            Signal s = execStep(step, ctx, eff);
            if (s != Signal.CONTINUE) return s;
        }
        return Signal.CONTINUE;
    }

    // ===================== 步骤分发 =====================

    @SuppressWarnings("unchecked")
    private Signal execStep(Step step, EvaluationContext ctx, GameContext eff) throws LogicException {
        int n = ctx.stepCount.incrementAndGet();
        if (n > ctx.maxSteps) {
            throw new LogicException("执行步数超过上限 (" + ctx.maxSteps + ")");
        }

        String type = step.type;
        Map<String, Object> p = step.params;

        switch (type) {
            case "set": {
                String target = str(p.get("target"));
                Object value = resolveValue(p.get("value"), ctx, eff);
                ctx.assign(target, value);
                return Signal.CONTINUE;
            }
            case "eval": {
                String target = str(p.get("target"));
                String expr = str(p.get("expression"));
                Object result = evalExpr(expr, ctx, eff);
                ctx.assign(target, result);
                return Signal.CONTINUE;
            }
            case "get_player":
                if (step.out != null) ctx.assign(step.out, (eff != null && eff.player != null) ? eff.player.getName() : "");
                return Signal.CONTINUE;
            case "get_difficulty":
                if (step.out != null) ctx.assign(step.out, eff != null ? eff.difficulty : 0);
                return Signal.CONTINUE;
            case "get_platform_size":
                if (step.out != null) ctx.assign(step.out, eff != null ? eff.platformSize : 0);
                return Signal.CONTINUE;
            case "get_mine_count":
                if (step.out != null) ctx.assign(step.out, eff != null ? eff.mineCount : 0);
                return Signal.CONTINUE;
            case "get_auto_flag":
                if (step.out != null) ctx.assign(step.out, (eff != null && eff.autoFlag) ? 1 : 0);
                return Signal.CONTINUE;
            case "random_int": {
                String target = str(p.get("target"));
                int min = asInt(resolveNumeric(p.get("min"), ctx, eff));
                int max = asInt(resolveNumeric(p.get("max"), ctx, eff));
                if (max < min) { int t = min; min = max; max = t; }
                int v = min + ThreadLocalRandom.current().nextInt(max - min + 1);
                ctx.assign(target, v);
                return Signal.CONTINUE;
            }
            case "random_chance": {
                String target = str(p.get("target"));
                int pct = asInt(resolveNumeric(p.get("percent"), ctx, eff));
                int v = (ThreadLocalRandom.current().nextInt(100) < pct) ? 1 : 0;
                ctx.assign(target, v);
                return Signal.CONTINUE;
            }
            case "check_permission": {
                String target = str(p.get("target"));
                String perm = str(p.get("permission"));
                boolean has = eff != null && eff.player != null && eff.player.hasPermission(perm);
                ctx.assign(target, has ? 1 : 0);
                return Signal.CONTINUE;
            }
            case "eco_balance": {
                String target = str(p.get("target"));
                double bal = (eff != null && eff.player != null) ? vault.getBalance(eff.player) : 0.0;
                ctx.assign(target, bal);
                return Signal.CONTINUE;
            }
            case "eco_give": {
                double amt = asDouble(resolveNumeric(p.get("amount"), ctx, eff));
                Player pl = resolvePlayer(p.get("player"), eff);
                if (pl != null) vault.deposit(pl, amt);
                return Signal.CONTINUE;
            }
            case "eco_take": {
                double amt = asDouble(resolveNumeric(p.get("amount"), ctx, eff));
                Player pl = resolvePlayer(p.get("player"), eff);
                if (pl != null) vault.withdraw(pl, amt);
                return Signal.CONTINUE;
            }
            case "log": {
                String lvl = str(p.get("level"));
                String msg = Expression.toStr(resolveValue(p.get("message"), ctx, eff));
                Level level = Level.INFO;
                try {
                    level = Level.parse(lvl.toUpperCase(Locale.ROOT));
                } catch (Exception ignored) {
                }
                plugin.getLogger().log(level, "[Logic] " + msg);
                return Signal.CONTINUE;
            }
            case "notify_admin": {
                String msg = Expression.toStr(resolveValue(p.get("message"), ctx, eff));
                notifyAdmins("§e[扫雷] " + msg);
                return Signal.CONTINUE;
            }
            case "if": {
                Object cond = evalExpr(str(p.get("condition")), ctx, eff);
                List<Step> thenSteps = step.steps("then");
                List<Step> elseSteps = step.steps("else");
                if (isTruthy(cond)) {
                    if (thenSteps != null) {
                        Signal s = execSteps(thenSteps, ctx, eff);
                        if (s != Signal.CONTINUE) return s;
                    }
                } else if (elseSteps != null) {
                    Signal s = execSteps(elseSteps, ctx, eff);
                    if (s != Signal.CONTINUE) return s;
                }
                return Signal.CONTINUE;
            }
            case "while": {
                int maxIter = step.has("max_iterations")
                        ? asInt(resolveNumeric(p.get("max_iterations"), ctx, eff)) : 10000;
                List<Step> body = step.steps("do");
                if (body == null) body = new ArrayList<>();
                int iter = 0;
                while (isTruthy(evalExpr(str(p.get("condition")), ctx, eff))) {
                    if (iter++ >= maxIter) {
                        plugin.getLogger().warning("[Logic] while 循环达到最大迭代次数 " + maxIter);
                        break;
                    }
                    Signal s = execSteps(body, ctx, eff);
                    if (s == Signal.RETURN) return Signal.RETURN;
                    if (s == Signal.BREAK) break;
                }
                return Signal.CONTINUE;
            }
            case "for": {
                Object range = p.get("range");
                int start = 0, end = 0;
                if (range instanceof List && ((List<?>) range).size() >= 2) {
                    start = asInt(resolveNumeric(((List<?>) range).get(0), ctx, eff));
                    end = asInt(resolveNumeric(((List<?>) range).get(1), ctx, eff));
                }
                String var = str(p.get("var"));
                int maxIter = step.has("max_iterations")
                        ? asInt(resolveNumeric(p.get("max_iterations"), ctx, eff)) : 10000;
                List<Step> body = step.steps("do");
                if (body == null) body = new ArrayList<>();
                int iter = 0;
                for (int i = start; i < end; i++) {
                    if (iter++ >= maxIter) {
                        plugin.getLogger().warning("[Logic] for 循环达到最大迭代次数");
                        break;
                    }
                    ctx.assign(var, i);
                    Signal s = execSteps(body, ctx, eff);
                    if (s == Signal.RETURN) return Signal.RETURN;
                    if (s == Signal.BREAK) break;
                }
                return Signal.CONTINUE;
            }
            case "for_each": {
                Object coll = lookup(str(p.get("target")), ctx);
                String var = str(p.get("var"));
                String keyVar = str(p.get("key_var"));
                int maxIter = step.has("max_iterations")
                        ? asInt(resolveNumeric(p.get("max_iterations"), ctx, eff)) : 10000;
                List<Step> body = step.steps("do");
                if (body == null) body = new ArrayList<>();
                int iter = 0;
                if (coll instanceof Map) {
                    for (Map.Entry<String, Object> e : ((Map<String, Object>) coll).entrySet()) {
                        if (iter++ >= maxIter) {
                            plugin.getLogger().warning("[Logic] for_each 达到最大迭代次数 " + maxIter);
                            break;
                        }
                        if (!keyVar.isEmpty()) ctx.assign(keyVar, e.getKey());
                        ctx.assign(var, e.getValue());
                        Signal s = execSteps(body, ctx, eff);
                        if (s == Signal.RETURN) return Signal.RETURN;
                        if (s == Signal.BREAK) break;
                    }
                } else if (coll instanceof List) {
                    for (Object item : (List<Object>) coll) {
                        if (iter++ >= maxIter) {
                            plugin.getLogger().warning("[Logic] for_each 达到最大迭代次数 " + maxIter);
                            break;
                        }
                        ctx.assign(var, item);
                        Signal s = execSteps(body, ctx, eff);
                        if (s == Signal.RETURN) return Signal.RETURN;
                        if (s == Signal.BREAK) break;
                    }
                } else {
                    throw new LogicException("for_each: target 不是列表或映射");
                }
                return Signal.CONTINUE;
            }
            case "break":
                return Signal.BREAK;
            case "list_create":
                ctx.assign(str(p.get("target")), new ArrayList<>());
                return Signal.CONTINUE;
            case "list_add": {
                Object list = lookup(str(p.get("target")), ctx);
                if (!(list instanceof List)) throw new LogicException("list_add: 目标不是列表");
                ((List<Object>) list).add(resolveValue(p.get("value"), ctx, eff));
                return Signal.CONTINUE;
            }
            case "list_set": {
                Object list = lookup(str(p.get("target")), ctx);
                if (!(list instanceof List)) throw new LogicException("list_set: 目标不是列表");
                int idx = asInt(resolveParam(p.get("index"), ctx, eff));
                ((List<Object>) list).set(idx, resolveValue(p.get("value"), ctx, eff));
                return Signal.CONTINUE;
            }
            case "list_get": {
                Object list = lookup(str(p.get("target")), ctx);
                if (!(list instanceof List)) throw new LogicException("list_get: 目标不是列表");
                int idx = asInt(resolveParam(p.get("index"), ctx, eff));
                Object v = (idx >= 0 && idx < ((List<?>) list).size()) ? ((List<?>) list).get(idx) : "";
                if (step.out != null) ctx.assign(step.out, v);
                return Signal.CONTINUE;
            }
            case "list_remove": {
                Object list = lookup(str(p.get("target")), ctx);
                if (list instanceof List) ((List<Object>) list).remove(asInt(resolveParam(p.get("index"), ctx, eff)));
                return Signal.CONTINUE;
            }
            case "list_size": {
                Object list = lookup(str(p.get("target")), ctx);
                int sz = (list instanceof List) ? ((List<?>) list).size() : 0;
                if (step.out != null) ctx.assign(step.out, sz);
                return Signal.CONTINUE;
            }
            case "map_create":
                ctx.assign(str(p.get("target")), new LinkedHashMap<>());
                return Signal.CONTINUE;
            case "map_put": {
                Object m = lookup(str(p.get("target")), ctx);
                if (!(m instanceof Map)) throw new LogicException("map_put: 目标不是映射");
                ((Map<String, Object>) m).put(str(p.get("key")), resolveValue(p.get("value"), ctx, eff));
                return Signal.CONTINUE;
            }
            case "map_get": {
                Object m = lookup(str(p.get("target")), ctx);
                Object v = "";
                if (m instanceof Map) {
                    Object got = ((Map<String, Object>) m).get(str(p.get("key")));
                    v = (got == null) ? "" : got;
                }
                if (step.out != null) ctx.assign(step.out, v);
                return Signal.CONTINUE;
            }
            case "map_remove": {
                Object m = lookup(str(p.get("target")), ctx);
                if (m instanceof Map) ((Map<String, Object>) m).remove(str(p.get("key")));
                return Signal.CONTINUE;
            }
            case "map_keys": {
                Object m = lookup(str(p.get("target")), ctx);
                List<Object> keys = new ArrayList<>();
                if (m instanceof Map) keys.addAll(((Map<String, Object>) m).keySet());
                if (step.out != null) ctx.assign(step.out, keys);
                return Signal.CONTINUE;
            }
            case "call": {
                String fname = str(p.get("function"));
                Object with = p.get("with");
                boolean inherit = !step.has("context") || isTruthy(resolveParam(p.get("context"), ctx, eff));
                Object ret = doCall(fname, with, ctx, eff, inherit);
                if (step.out != null) ctx.assign(step.out, ret);
                return Signal.CONTINUE;
            }
            case "return": {
                Object v = resolveValue(p.get("value"), ctx, eff);
                ctx.returnValue = (v == null) ? "" : v;
                return Signal.RETURN;
            }
            case "store_set": {
                String key = str(p.get("key"));
                Object v = resolveValue(p.get("value"), ctx, eff);
                persistence.set(key, v);
                return Signal.CONTINUE;
            }
            case "store_get": {
                String key = str(p.get("key"));
                Object v = persistence.get(key);
                if (step.out != null) ctx.assign(step.out, v);
                return Signal.CONTINUE;
            }
            case "store_remove":
                persistence.remove(str(p.get("key")));
                return Signal.CONTINUE;
            default:
                throw new LogicException("未知的步骤类型: " + type);
        }
    }

    // ===================== 函数调用 =====================

    private Object doCall(String fname, Object with, EvaluationContext ctx, GameContext eff, boolean inheritCtx)
            throws LogicException {
        LogicFunction func = config.getFunction(fname);
        if (func == null) {
            throw new LogicException("call: 未找到函数 '" + fname + "'");
        }
        List<Object> args = new ArrayList<>();
        if (with instanceof List) {
            for (Object o : (List<?>) with) args.add(resolveParam(o, ctx, eff));
        } else if (with instanceof Map) {
            Map<String, Object> named = (Map<String, Object>) with;
            if (func.parameters != null) {
                for (String pn : func.parameters) {
                    args.add(named.containsKey(pn) ? resolveParam(named.get(pn), ctx, eff) : null);
                }
            }
        }
        GameContext childGame = inheritCtx ? eff : null;
        return executeFunction(func, args, ctx, childGame);
    }

    // ===================== 占位符 / 求值辅助 =====================

    private Object lookup(String name, EvaluationContext ctx) throws LogicException {
        Object v = ctx.lookupRaw(name);
        if (v == null) {
            // 全局变量允许未初始化：规范内置示例依赖 global.weights_init 在首次运行时未定义，
            // 通过 "!= 1" 判断后初始化。故未定义的 global. 读取视为空串，使条件比较可正确分支。
            if (name.startsWith("global.")) return "";
            throw new LogicException("未定义的变量: " + name);
        }
        return v;
    }

    /** 解析步骤参数字面值（数字 / 列表 / 映射原样；字符串按 占位符→表达式→变量→字面量 解析）。 */
    private Object resolveParam(Object raw, EvaluationContext ctx, GameContext eff) throws LogicException {
        if (raw instanceof Number || raw instanceof Boolean || raw instanceof List || raw instanceof Map) {
            return raw;
        }
        if (raw instanceof String) {
            String s = (String) raw;
            Matcher full = PLACEHOLDER_FULL.matcher(s.trim());
            if (full.matches()) {
                return resolvePlaceholderContent(full.group(1), ctx);
            }
            if (PLACEHOLDER_ANY.matcher(s).find()) {
                return evalExpr(s, ctx, eff); // 含占位符 → 作为表达式求值（支持 "{base} + 1"）
            }
            if (VAR_NAME.matcher(s).matches() && ctx.exists(s)) {
                return ctx.lookupRaw(s);
            }
            return s; // 字面量
        }
        return raw;
    }

    /** 解析数值参数：数字原样；含占位符字符串作为表达式求值；否则按数字解析。 */
    private Object resolveNumeric(Object raw, EvaluationContext ctx, GameContext eff) throws LogicException {
        if (raw instanceof Number) return raw;
        if (raw instanceof String) {
            String s = (String) raw;
            if (PLACEHOLDER_ANY.matcher(s).find()) {
                return evalExpr(s, ctx, eff);
            }
            return Expression.toDouble(s);
        }
        return Expression.toDouble(raw);
    }

    /** 解析 set/return/list/map/message 的 value：字符串按模板解析（单占位符保留对象类型）。 */
    private Object resolveValue(Object raw, EvaluationContext ctx, GameContext eff) throws LogicException {
        if (raw instanceof String) {
            return resolveTemplate((String) raw, ctx, eff);
        }
        return raw;
    }

    /** 模板解析：若整体为单个占位符则保留对象类型；否则将所有占位符替换为字符串。 */
    private Object resolveTemplate(String s, EvaluationContext ctx, GameContext eff) throws LogicException {
        String trimmed = s.trim();
        Matcher full = PLACEHOLDER_FULL.matcher(trimmed);
        if (full.matches()) {
            return resolvePlaceholderContent(full.group(1), ctx);
        }
        Matcher m = PLACEHOLDER_ANY.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            Object val = resolvePlaceholderContent(m.group(1), ctx);
            m.appendReplacement(sb, Matcher.quoteReplacement(Expression.toStr(val)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 解析占位符内容：支持 {@code $name} 间接引用（name 的值作为变量名再查一次）。 */
    private Object resolvePlaceholderContent(String content, EvaluationContext ctx) throws LogicException {
        content = content.trim();
        if (content.startsWith("$")) {
            String inner = content.substring(1).trim();
            Object nameVal = lookup(inner, ctx);
            String targetName = Expression.toStr(nameVal);
            return lookup(targetName, ctx);
        }
        return lookup(content, ctx);
    }

    /** 表达式中的占位符替换：数字裸插入，字符串仅在未被引号包裹时自动加引号。 */
    private String substituteExpr(String expr, EvaluationContext ctx, GameContext eff) throws LogicException {
        if (expr == null) return "";
        Matcher m = PLACEHOLDER_ANY.matcher(expr);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            Object val = resolvePlaceholderContent(m.group(1), ctx);
            String replacement;
            if (val instanceof Number) {
                replacement = Expression.toStr(val);
            } else if (val instanceof Boolean) {
                replacement = ((Boolean) val) ? "1" : "0";
            } else {
                String str = Expression.toStr(val);
                int start = m.start();
                int end = m.end();
                boolean quoted = (start > 0 && expr.charAt(start - 1) == '"')
                        && (end < expr.length() && expr.charAt(end) == '"');
                replacement = quoted ? str : ("\"" + str.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private Object evalExpr(String expr, EvaluationContext ctx, GameContext eff) throws LogicException {
        String sub = substituteExpr(expr, ctx, eff);
        long deadline = System.nanoTime() + config.getMaxEvalMs() * 1_000_000L;
        return Expression.evaluate(sub, deadline);
    }

    private Player resolvePlayer(Object raw, GameContext eff) {
        if (raw == null) return (eff != null) ? eff.player : null;
        if (raw instanceof Player) return (Player) raw;
        if (raw instanceof String) {
            String name = (String) raw;
            if (name.isEmpty()) return (eff != null) ? eff.player : null;
            return Bukkit.getPlayerExact(name);
        }
        return (eff != null) ? eff.player : null;
    }

    private void notifyAdmins(String msg) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("minesweeper.admin")) {
                p.sendMessage(msg);
            }
        }
        plugin.getLogger().info(msg);
    }

    // ===================== 基础转换 =====================

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private int asInt(Object o) throws LogicException {
        if (o instanceof Integer) return (Integer) o;
        if (o instanceof Long) return (int) (long) (Long) o;
        if (o instanceof Double) return (int) Math.floor((Double) o);
        return (int) Math.floor(Expression.toDouble(o));
    }

    private double asDouble(Object o) throws LogicException {
        return Expression.toDouble(o);
    }

    private boolean isTruthy(Object o) {
        return Expression.isTruthy(o);
    }
}
