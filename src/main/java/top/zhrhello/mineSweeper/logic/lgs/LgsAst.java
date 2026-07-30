package top.zhrhello.mineSweeper.logic.lgs;

import java.util.List;

/**
 * LogicStep 抽象语法树（AST）节点定义。
 *
 * 使用 Java 17 record 定义不可变 AST 节点。分为三大类：
 * <ol>
 *   <li>{@link StepDef} / {@link ModuleDef} —— 顶层定义</li>
 *   <li>{@link Stmt} —— 语句（set / 指令调用 / mod 调用 / jump / return / exit / 控制流）</li>
 *   <li>{@link Expr} —— 表达式（字面量 / 变量 / 运算 / 函数调用 / 列表）</li>
 * </ol>
 *
 * 所有节点均携带源码行号，便于运行时错误定位。
 */
public final class LgsAst {

    private LgsAst() {}

    // ==================== 顶层定义 ====================

    /** step 定义：名称、参数列表、语句体。可被 jump 跳转，也可作为入口。 */
    public record StepDef(String name, List<String> params, List<Stmt> body, int line) {}

    /** module 定义：名称、参数列表、语句体。通过 mod.名() 调用，必须有 return。 */
    public record ModuleDef(String name, List<String> params, List<Stmt> body, int line) {}

    // ==================== 语句 ====================

    public sealed interface Stmt permits SetStmt, InstrCallStmt, ModCallStmt, JumpStmt,
            ReturnStmt, ExitStmt, IfStmt, WhileStmt, RepeatStmt, ForStmt, TryStmt {

        /** 源码行号。 */
        int line();
    }

    /** set 变量名 = 表达式 */
    public record SetStmt(String varName, Expr value, int line) implements Stmt {}

    /**
     * 指令调用：指令名 参数1, 参数2, ... -&gt; 输出变量
     * outVar 为 null 表示无输出捕获。
     */
    public record InstrCallStmt(String name, List<Expr> args, String outVar, int line) implements Stmt {}

    /** mod.模块名(参数...) -> 输出变量。outVar 为 null 表示不捕获。 */
    public record ModCallStmt(String modName, List<Expr> args, String outVar, int line) implements Stmt {}

    /** jump.步骤名(参数...) —— 非返回跳转。 */
    public record JumpStmt(String target, List<Expr> args, int line) implements Stmt {}

    /** return 表达式。value 为 null 表示返回 null。 */
    public record ReturnStmt(Expr value, int line) implements Stmt {}

    /** exit while / exit for / exit repeat。loopType 为 "while" / "for" / "repeat"。 */
    public record ExitStmt(String loopType, int line) implements Stmt {}

    /** if / else if / else 分支。 */
    public record IfStmt(List<Branch> branches, List<Stmt> elseBody, int line) implements Stmt {

        /** 单个条件分支：条件 + 语句体。 */
        public record Branch(Expr cond, List<Stmt> body) {}
    }

    /** while 条件 do ... end while */
    public record WhileStmt(Expr cond, List<Stmt> body, int line) implements Stmt {}

    /** repeat ... until 条件 */
    public record RepeatStmt(List<Stmt> body, Expr until, int line) implements Stmt {}

    /** for 变量 in 列表 do ... end for */
    public record ForStmt(String varName, Expr listExpr, List<Stmt> body, int line) implements Stmt {}

    /** try ... catch ... end try（可选特性） */
    public record TryStmt(List<Stmt> tryBody, List<Stmt> catchBody, int line) implements Stmt {}

    // ==================== 表达式 ====================

    public sealed interface Expr permits NumExpr, StrExpr, BoolExpr, NullExpr,
            VarExpr, ListExpr, UnaryExpr, BinaryExpr, CallExpr {

        /** 源码行号。 */
        int line();
    }

    /** 数字字面量。 */
    public record NumExpr(double value, int line) implements Expr {}

    /** 字符串字面量。 */
    public record StrExpr(String value, int line) implements Expr {}

    /** 布尔字面量：true / false。 */
    public record BoolExpr(boolean value, int line) implements Expr {}

    /** null 字面量。 */
    public record NullExpr(int line) implements Expr {}

    /** 变量引用（标识符）。 */
    public record VarExpr(String name, int line) implements Expr {}

    /** 列表字面量：[expr, expr, ...]。 */
    public record ListExpr(List<Expr> elements, int line) implements Expr {}

    /** 一元运算：!（逻辑非）/ -（负号）。 */
    public record UnaryExpr(String op, Expr operand, int line) implements Expr {}

    /**
     * 二元运算：+ - * / &lt; &gt; &lt;= &gt;= == != and or。
     * 字符串拼接也使用 "+"。
     */
    public record BinaryExpr(String op, Expr left, Expr right, int line) implements Expr {}

    /** 函数调用（表达式内）：name(args)。如 floor(x)、max(a, b)。 */
    public record CallExpr(String name, List<Expr> args, int line) implements Expr {}
}
