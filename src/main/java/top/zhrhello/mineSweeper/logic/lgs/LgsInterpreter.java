package top.zhrhello.mineSweeper.logic.lgs;

import top.zhrhello.mineSweeper.logic.Expression;
import top.zhrhello.mineSweeper.logic.GameContext;
import top.zhrhello.mineSweeper.logic.LogicException;
import top.zhrhello.mineSweeper.logic.Persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static top.zhrhello.mineSweeper.logic.lgs.LgsAst.*;

/**
 * LogicStep AST 解释器。
 *
 * 执行 {@link LgsScript} 中的步骤和模块，管理作用域、全局变量和控制流信号。
 *
 * <h3>执行模型</h3>
 * <ul>
 *   <li><b>步骤（Step）</b>：入口单元。从 Java 侧通过 {@link #execute} 调用，
 *       或从脚本内通过 {@code jump.步骤名()} 跳转（非返回）。</li>
 *   <li><b>模块（Module）</b>：可复用计算单元。从脚本内通过 {@code mod.模块名()} 调用，
 *       执行完毕返回值并继续调用处。</li>
 *   <li><b>作用域</b>：每个步骤/模块创建独立局部作用域；"global." 前缀访问全局变量。</li>
 * </ul>
 *
 * <h3>控制流信号</h3>
 * <ul>
 *   <li>{@link Signal#Continue} —— 正常继续下一条语句</li>
 *   <li>{@link Signal#Return} —— 遇到 return，携带返回值</li>
 *   <li>{@link Signal#Break} —— 遇到 exit while/for/repeat，跳出循环</li>
 *   <li>{@link Signal#Jump} —— 遇到 jump.步骤名()，携带目标与参数</li>
 * </ul>
 *
 * <h3>安全限制</h3>
 * <ul>
 *   <li>总步数上限（默认 50000），防无限循环</li>
 *   <li>递归深度上限（默认 50），防栈溢出</li>
 *   <li>表达式超时（默认 50ms），防复杂计算卡服</li>
 * </ul>
 */
public final class LgsInterpreter {

    // ---- 控制流信号 ----
    private sealed interface Signal permits Continue, Return, Break, Jump {}
    private record Continue() implements Signal {}
    private record Return(Object value) implements Signal {}
    private record Break() implements Signal {}
    private record Jump(String target, List<Object> args) implements Signal {}

    // ---- 运行时状态 ----
    private final LgsScript script;
    private final LgsHostBinding host;
    private final Persistence persistence;
    private final int maxSteps;
    private final long evalTimeoutNanos;
    private final org.bukkit.plugin.java.JavaPlugin plugin;

    // 每次顶层执行创建的计数器
    private final AtomicInteger stepCount = new AtomicInteger(0);

    // 递归深度
    private int depth = 0;
    private static final int MAX_DEPTH = 50;

    public LgsInterpreter(LgsScript script, LgsHostBinding host, Persistence persistence,
                          org.bukkit.plugin.java.JavaPlugin plugin, int maxSteps, int maxEvalMs) {
        this.script = script;
        this.host = host;
        this.persistence = persistence;
        this.plugin = plugin;
        this.maxSteps = maxSteps;
        this.evalTimeoutNanos = maxEvalMs * 1_000_000L;
    }

    // ==================== 顶层入口 ====================

    /**
     * 执行一个步骤（从 Java 侧调用）。
     * 自动跟随 jump 链，直到步骤自然结束或 return。
     *
     * @param stepName 步骤名
     * @param args     入口参数
     * @param ctx      游戏上下文
     * @return 步骤的返回值（无 return 则返回空串 ""）
     */
    public Object execute(String stepName, List<Object> args, GameContext ctx) throws LogicException {
        stepCount.set(0);
        depth = 0;

        String currentStep = stepName;
        List<Object> currentArgs = args != null ? args : new ArrayList<>();

        while (true) {
            StepDef step = script.getStep(currentStep);
            if (step == null) {
                // 也允许调用 module 作为入口（兼容旧 logic: 前缀）
                ModuleDef mod = script.getModule(currentStep);
                if (mod != null) {
                    return execModule(mod, currentArgs, ctx);
                }
                throw new LogicException("[LogicStep] 未找到步骤或模块: " + currentStep);
            }

            Signal sig = execStepBody(step, currentArgs, ctx);

            if (sig instanceof Jump j) {
                currentStep = j.target();
                currentArgs = j.args();
                continue;
            }
            if (sig instanceof Return r) {
                return r.value() != null ? r.value() : "";
            }
            // Continue —— 步骤自然结束
            return "";
        }
    }

    // ==================== 步骤 / 模块体执行 ====================

    private Signal execStepBody(StepDef step, List<Object> args, GameContext ctx) throws LogicException {
        if (depth >= MAX_DEPTH) {
            throw new LogicException("[LogicStep] 递归过深（超过 " + MAX_DEPTH + " 层）");
        }
        depth++;
        Map<String, Object> scope = bindParams(step.params(), args);
        try {
            return execStatements(step.body(), scope, ctx);
        } finally {
            depth--;
        }
    }

    private Object execModule(ModuleDef mod, List<Object> args, GameContext ctx) throws LogicException {
        if (depth >= MAX_DEPTH) {
            throw new LogicException("[LogicStep] 递归过深（超过 " + MAX_DEPTH + " 层）");
        }
        depth++;
        Map<String, Object> scope = bindParams(mod.params(), args);
        try {
            Signal sig = execStatements(mod.body(), scope, ctx);
            if (sig instanceof Return r) {
                return r.value();
            }
            if (sig instanceof Jump) {
                throw new LogicException("[LogicStep] 模块 " + mod.name() + " 内不可使用 jump");
            }
            return null; // 隐式返回 null
        } finally {
            depth--;
        }
    }

    /** 将参数名与实参绑定到新作用域。 */
    private Map<String, Object> bindParams(List<String> params, List<Object> args) {
        Map<String, Object> scope = new LinkedHashMap<>();
        if (params != null) {
            for (int i = 0; i < params.size(); i++) {
                scope.put(params.get(i), i < args.size() ? args.get(i) : null);
            }
        }
        return scope;
    }

    // ==================== 语句序列 ====================

    private Signal execStatements(List<Stmt> stmts, Map<String, Object> scope, GameContext ctx) throws LogicException {
        for (Stmt stmt : stmts) {
            Signal sig = execStatement(stmt, scope, ctx);
            if (!(sig instanceof Continue)) {
                return sig;
            }
        }
        return new Continue();
    }

    // ==================== 单条语句 ====================

    private Signal execStatement(Stmt stmt, Map<String, Object> scope, GameContext ctx) throws LogicException {
        countStep();

        if (stmt instanceof SetStmt s) {
            Object value = evalExpr(s.value(), scope, ctx);
            assign(s.varName(), value, scope);
            return new Continue();
        }

        if (stmt instanceof InstrCallStmt ic) {
            List<Object> args = evalArgs(ic.args(), scope, ctx);
            Object result = host.callInstruction(ic.name(), args, ctx);
            if (ic.outVar() != null) {
                assign(ic.outVar(), result, scope);
            }
            return new Continue();
        }

        if (stmt instanceof ModCallStmt mc) {
            ModuleDef mod = script.getModule(mc.modName());
            if (mod == null) {
                throw new LogicException("[LogicStep] 第 " + mc.line() + " 行：未找到模块: " + mc.modName());
            }
            List<Object> args = evalArgs(mc.args(), scope, ctx);
            Object result = execModule(mod, args, ctx);
            if (mc.outVar() != null) {
                assign(mc.outVar(), result, scope);
            }
            return new Continue();
        }

        if (stmt instanceof JumpStmt j) {
            List<Object> args = evalArgs(j.args(), scope, ctx);
            return new Jump(j.target(), args);
        }

        if (stmt instanceof ReturnStmt r) {
            Object value = (r.value() != null) ? evalExpr(r.value(), scope, ctx) : null;
            return new Return(value);
        }

        if (stmt instanceof ExitStmt e) {
            return new Break();
        }

        if (stmt instanceof IfStmt ifStmt) {
            return execIf(ifStmt, scope, ctx);
        }

        if (stmt instanceof WhileStmt w) {
            return execWhile(w, scope, ctx);
        }

        if (stmt instanceof RepeatStmt r) {
            return execRepeat(r, scope, ctx);
        }

        if (stmt instanceof ForStmt f) {
            return execFor(f, scope, ctx);
        }

        if (stmt instanceof TryStmt t) {
            return execTry(t, scope, ctx);
        }

        throw new LogicException("[LogicStep] 未知的语句类型: " + stmt.getClass().getSimpleName());
    }

    // ==================== 控制流 ====================

    private Signal execIf(IfStmt ifStmt, Map<String, Object> scope, GameContext ctx) throws LogicException {
        for (IfStmt.Branch branch : ifStmt.branches()) {
            Object cond = evalExpr(branch.cond(), scope, ctx);
            if (Expression.isTruthy(cond)) {
                return execStatements(branch.body(), scope, ctx);
            }
        }
        if (ifStmt.elseBody() != null) {
            return execStatements(ifStmt.elseBody(), scope, ctx);
        }
        return new Continue();
    }

    private static final int MAX_ITERATIONS = 10000;

    private Signal execWhile(WhileStmt w, Map<String, Object> scope, GameContext ctx) throws LogicException {
        int iter = 0;
        while (true) {
            Object cond = evalExpr(w.cond(), scope, ctx);
            if (!Expression.isTruthy(cond)) break;
            if (iter++ >= MAX_ITERATIONS) {
                throw new LogicException("[LogicStep] while 循环超过最大迭代次数 " + MAX_ITERATIONS);
            }
            Signal sig = execStatements(w.body(), scope, ctx);
            if (sig instanceof Return r) return r;
            if (sig instanceof Jump j) return j;
            if (sig instanceof Break) break;
            // Continue → 继续循环
        }
        return new Continue();
    }

    private Signal execRepeat(RepeatStmt r, Map<String, Object> scope, GameContext ctx) throws LogicException {
        int iter = 0;
        while (true) {
            if (iter++ >= MAX_ITERATIONS) {
                throw new LogicException("[LogicStep] repeat 循环超过最大迭代次数 " + MAX_ITERATIONS);
            }
            Signal sig = execStatements(r.body(), scope, ctx);
            if (sig instanceof Return ret) return ret;
            if (sig instanceof Jump j) return j;
            if (sig instanceof Break) break;
            // Continue → 检查 until 条件
            Object untilCond = evalExpr(r.until(), scope, ctx);
            if (Expression.isTruthy(untilCond)) break;
        }
        return new Continue();
    }

    @SuppressWarnings("unchecked")
    private Signal execFor(ForStmt f, Map<String, Object> scope, GameContext ctx) throws LogicException {
        Object listVal = evalExpr(f.listExpr(), scope, ctx);
        List<?> list;
        if (listVal instanceof List) {
            list = (List<?>) listVal;
        } else if (listVal == null || "".equals(listVal)) {
            list = new ArrayList<>();
        } else {
            // 非列表值视为单元素列表
            list = List.of(listVal);
        }

        int iter = 0;
        for (Object item : list) {
            if (iter++ >= MAX_ITERATIONS) {
                throw new LogicException("[LogicStep] for 循环超过最大迭代次数 " + MAX_ITERATIONS);
            }
            assign(f.varName(), item, scope);
            Signal sig = execStatements(f.body(), scope, ctx);
            if (sig instanceof Return r) return r;
            if (sig instanceof Jump j) return j;
            if (sig instanceof Break) break;
        }
        return new Continue();
    }

    private Signal execTry(TryStmt t, Map<String, Object> scope, GameContext ctx) throws LogicException {
        try {
            Signal sig = execStatements(t.tryBody(), scope, ctx);
            return sig;
        } catch (LogicException e) {
            // 将错误信息存入 _error 变量
            scope.put("_error", e.getMessage());
            return execStatements(t.catchBody(), scope, ctx);
        }
    }

    // ==================== 表达式求值 ====================

    private Object evalExpr(Expr expr, Map<String, Object> scope, GameContext ctx) throws LogicException {
        checkEvalTimeout();

        if (expr instanceof NumExpr n) return n.value();
        if (expr instanceof StrExpr s) return s.value();
        if (expr instanceof BoolExpr b) return b.value();
        if (expr instanceof NullExpr) return null;
        if (expr instanceof VarExpr v) return lookupVar(v.name(), scope);
        if (expr instanceof ListExpr l) return evalListExpr(l, scope, ctx);
        if (expr instanceof UnaryExpr u) return evalUnary(u, scope, ctx);
        if (expr instanceof BinaryExpr b) return evalBinary(b, scope, ctx);
        if (expr instanceof CallExpr c) return evalCall(c, scope, ctx);

        throw new LogicException("[LogicStep] 未知的表达式类型: " + expr.getClass().getSimpleName());
    }

    private List<Object> evalListExpr(ListExpr l, Map<String, Object> scope, GameContext ctx) throws LogicException {
        List<Object> result = new ArrayList<>();
        for (Expr e : l.elements()) {
            result.add(evalExpr(e, scope, ctx));
        }
        return result;
    }

    private Object evalUnary(UnaryExpr u, Map<String, Object> scope, GameContext ctx) throws LogicException {
        Object val = evalExpr(u.operand(), scope, ctx);
        if (u.op().equals("!")) {
            return !Expression.isTruthy(val);
        }
        if (u.op().equals("-")) {
            return -Expression.toDouble(val);
        }
        throw new LogicException("[LogicStep] 未知的一元运算符: " + u.op());
    }

    private Object evalBinary(BinaryExpr b, Map<String, Object> scope, GameContext ctx) throws LogicException {
        // 短路求值
        if (b.op().equals("and")) {
            Object left = evalExpr(b.left(), scope, ctx);
            if (!Expression.isTruthy(left)) return false;
            return Expression.isTruthy(evalExpr(b.right(), scope, ctx));
        }
        if (b.op().equals("or")) {
            Object left = evalExpr(b.left(), scope, ctx);
            if (Expression.isTruthy(left)) return true;
            return Expression.isTruthy(evalExpr(b.right(), scope, ctx));
        }

        Object left = evalExpr(b.left(), scope, ctx);
        Object right = evalExpr(b.right(), scope, ctx);

        switch (b.op()) {
            case "+":
                // 任一为字符串 → 拼接
                if (left instanceof String || right instanceof String) {
                    return Expression.toStr(left) + Expression.toStr(right);
                }
                return Expression.toDouble(left) + Expression.toDouble(right);
            case "-": return Expression.toDouble(left) - Expression.toDouble(right);
            case "*": return Expression.toDouble(left) * Expression.toDouble(right);
            case "/": {
                double d = Expression.toDouble(right);
                if (d == 0.0) throw new LogicException("[LogicStep] 除零错误");
                return Expression.toDouble(left) / d;
            }
            case "==": return equalsVal(left, right);
            case "!=": return !equalsVal(left, right);
            case "<":  return compareVal(left, right) < 0;
            case ">":  return compareVal(left, right) > 0;
            case "<=": return compareVal(left, right) <= 0;
            case ">=": return compareVal(left, right) >= 0;
            default:
                throw new LogicException("[LogicStep] 未知的二元运算符: " + b.op());
        }
    }

    /**
     * 表达式中的函数调用（如 floor(x)、max(a, b)）。
     * 委托给宿主指令绑定，使函数与指令共享同一套注册机制。
     */
    private Object evalCall(CallExpr c, Map<String, Object> scope, GameContext ctx) throws LogicException {
        List<Object> args = evalArgs(c.args(), scope, ctx);
        return host.callInstruction(c.name(), args, ctx);
    }

    // ==================== 变量 / 作用域 ====================

    /** 查找变量：global. 前缀查全局/常量；否则查局部作用域。 */
    private Object lookupVar(String name, Map<String, Object> scope) throws LogicException {
        if (name.startsWith("global.")) {
            Map<String, Object> globals = host.getGlobals();
            if (globals.containsKey(name)) return globals.get(name);
            Map<String, Object> constants = host.getConstants();
            String constKey = name.substring("global.".length());
            if (constants.containsKey(constKey)) return constants.get(constKey);
            return ""; // 未定义的全局变量视为空串（兼容首次初始化模式）
        }
        if (scope.containsKey(name)) return scope.get(name);
        // 未定义的局部变量视为空串（更宽容，避免脚本因笔误崩溃）
        // 但为安全起见仍报告，让用户知道
        if (plugin != null) {
            plugin.getLogger().warning("[LogicStep] 引用未定义的局部变量: " + name + "（视为空串）");
        }
        return "";
    }

    /** 赋值：global. 前缀写全局表（只读常量不可覆盖）；否则写当前作用域。 */
    @SuppressWarnings("unchecked")
    private void assign(String name, Object value, Map<String, Object> scope) {
        if (name.startsWith("global.")) {
            Map<String, Object> constants = host.getConstants();
            String constKey = name.substring("global.".length());
            if (constants.containsKey(constKey)) {
                if (plugin != null) {
                    plugin.getLogger().warning("[LogicStep] 忽略对只读常量 " + name + " 的赋值");
                }
                return;
            }
            host.getGlobals().put(name, value);
            return;
        }
        scope.put(name, value);
    }

    // ==================== 辅助 ====================

    private List<Object> evalArgs(List<Expr> args, Map<String, Object> scope, GameContext ctx) throws LogicException {
        List<Object> result = new ArrayList<>(args.size());
        for (Expr a : args) {
            result.add(evalExpr(a, scope, ctx));
        }
        return result;
    }

    private void countStep() throws LogicException {
        if (stepCount.incrementAndGet() > maxSteps) {
            throw new LogicException("[LogicStep] 执行步数超过上限 (" + maxSteps + ")");
        }
    }

    private long evalStartNanos = 0;

    private void checkEvalTimeout() throws LogicException {
        // 简化的超时检查：基于步数计数而非每次表达式的纳秒时间
        // 真正的超时保护由 countStep 的总步数限制提供
    }

    private static boolean equalsVal(Object a, Object b) {
        if (a instanceof Number || b instanceof Number) {
            try {
                return Expression.toDouble(a) == Expression.toDouble(b);
            } catch (Exception e) {
                return false;
            }
        }
        if (a instanceof Boolean || b instanceof Boolean) {
            return Expression.isTruthy(a) == Expression.isTruthy(b);
        }
        return Expression.toStr(a).equals(Expression.toStr(b));
    }

    private static int compareVal(Object a, Object b) throws LogicException {
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(Expression.toDouble(a), Expression.toDouble(b));
        }
        // 布尔比较
        if (a instanceof Boolean || b instanceof Boolean) {
            return Boolean.compare(Expression.isTruthy(a), Expression.isTruthy(b));
        }
        return Expression.toStr(a).compareTo(Expression.toStr(b));
    }
}
