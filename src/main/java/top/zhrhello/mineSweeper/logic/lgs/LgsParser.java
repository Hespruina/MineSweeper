package top.zhrhello.mineSweeper.logic.lgs;

import top.zhrhello.mineSweeper.logic.LogicException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static top.zhrhello.mineSweeper.logic.lgs.LgsAst.*;
import static top.zhrhello.mineSweeper.logic.lgs.LgsToken.Type;

/**
 * LogicStep 递归下降解析器。
 *
 * 将 {@link LgsLexer} 产生的 Token 序列解析为 {@link LgsAst} 节点树。
 *
 * 解析流程：
 * <pre>
 *   parseScript()  →  List&lt;StepDef | ModuleDef&gt;
 *     parseStepDef()   /  parseModuleDef()
 *       parseStatements()  →  List&lt;Stmt&gt;
 *         parseStatement()
 *           parseExpression()  →  Expr  （优先级爬升）
 * </pre>
 *
 * 表达式优先级（从低到高）：
 *   or → and → 比较 → 加减 → 乘除 → 一元 → 主要
 */
public final class LgsParser {

    private final List<LgsToken> tokens;
    private int pos = 0;

    public LgsParser(List<LgsToken> tokens) {
        this.tokens = tokens;
    }

    // ==================== 入口：解析整个脚本 ====================

    /** 解析脚本，返回顶层定义列表（StepDef / ModuleDef）。 */
    public List<Object> parseScript() throws LogicException {
        List<Object> defs = new ArrayList<>();
        while (!isAtEnd()) {
            if (match(Type.STEP)) {
                defs.add(parseStepDef());
            } else if (match(Type.MODULE)) {
                defs.add(parseModuleDef());
            } else {
                throw error(peek(), "期望 'step' 或 'module'，但遇到 '" + peek().text + "'");
            }
        }
        return defs;
    }

    // ==================== Step / Module 定义 ====================

    private StepDef parseStepDef() throws LogicException {
        LgsToken nameTok = expect(Type.IDENT, "step 名称");
        List<String> params = parseParamList();
        List<Stmt> body = parseStatements("end");
        expect(Type.END, "end");
        expect(Type.STEP, "step");
        return new StepDef(nameTok.text, params, body, nameTok.line);
    }

    private ModuleDef parseModuleDef() throws LogicException {
        LgsToken nameTok = expect(Type.IDENT, "module 名称");
        List<String> params = parseParamList();
        List<Stmt> body = parseStatements("end");
        expect(Type.END, "end");
        expect(Type.MODULE, "module");
        return new ModuleDef(nameTok.text, params, body, nameTok.line);
    }

    /** 解析可选的参数列表：(param1, param2, ...) 或无参数。 */
    private List<String> parseParamList() throws LogicException {
        List<String> params = new ArrayList<>();
        if (!match(Type.LPAREN)) return params;
        if (!check(Type.RPAREN)) {
            do {
                LgsToken p = expect(Type.IDENT, "参数名");
                params.add(p.text);
            } while (match(Type.COMMA));
        }
        expect(Type.RPAREN, ")");
        return params;
    }

    // ==================== 语句序列 ====================

    /**
     * 解析语句序列，直到遇到指定的终止关键字（不消费终止关键字）。
     * 终止符为 "end"、"else"、"else if"、"until"、"catch" 之一。
     */
    private List<Stmt> parseStatements(String terminator) throws LogicException {
        List<Stmt> stmts = new ArrayList<>();
        Set<String> terms = new HashSet<>();
        terms.add(terminator);
        // 也可能在 else / catch 处终止
        terms.add("else");
        terms.add("catch");

        while (!isAtEnd()) {
            if (isTerminator(terminator)) break;
            stmts.add(parseStatement());
        }
        return stmts;
    }

    /** 判断当前是否为语句序列终止符。 */
    private boolean isTerminator(String terminator) {
        LgsToken t = peek();
        if (terminator.equals("end") && t.type == Type.END) return true;
        if (terminator.equals("else") && t.type == Type.ELSE) return true;
        if (terminator.equals("until") && t.type == Type.UNTIL) return true;
        if (terminator.equals("catch") && t.type == Type.CATCH) return true;
        // else if 也可作为终止符
        if (t.type == Type.ELSE && peek2().type == Type.IF) return true;
        // 单独的 else（if/else if/else 结构体的最终 else）同样是分支体的终止符
        if (t.type == Type.ELSE) return true;
        return false;
    }

    // ==================== 单条语句 ====================

    private Stmt parseStatement() throws LogicException {
        LgsToken t = peek();

        // set 变量 = 表达式
        if (t.type == Type.SET) {
            advance();
            String name = parseScopedName("set 后的变量名");
            expect(Type.ASSIGN, "=");
            Expr value = parseExpression();
            return new SetStmt(name, value, t.line);
        }

        // return [表达式]
        if (t.type == Type.RETURN) {
            advance();
            // return 后可为空（返回 null）或跟表达式
            if (isStmtEnd()) {
                return new ReturnStmt(null, t.line);
            }
            Expr value = parseExpression();
            return new ReturnStmt(value, t.line);
        }

        // exit while / exit for / exit repeat
        if (t.type == Type.EXIT) {
            advance();
            LgsToken loop = peek();
            String loopType;
            if (loop.type == Type.WHILE) { loopType = "while"; advance(); }
            else if (loop.type == Type.FOR) { loopType = "for"; advance(); }
            else if (loop.type == Type.REPEAT) { loopType = "repeat"; advance(); }
            else throw error(loop, "exit 后必须是 while / for / repeat");
            return new ExitStmt(loopType, t.line);
        }

        // if 条件 then ... else if ... else ... end if
        if (t.type == Type.IF) {
            return parseIf();
        }

        // while 条件 do ... end while
        if (t.type == Type.WHILE) {
            advance();
            Expr cond = parseExpression();
            expect(Type.DO, "do");
            List<Stmt> body = parseStatements("end");
            expect(Type.END, "end");
            expect(Type.WHILE, "while");
            return new WhileStmt(cond, body, t.line);
        }

        // repeat ... until 条件
        if (t.type == Type.REPEAT) {
            advance();
            List<Stmt> body = parseStatements("until");
            expect(Type.UNTIL, "until");
            Expr until = parseExpression();
            return new RepeatStmt(body, until, t.line);
        }

        // for 变量 in 列表 do ... end for
        if (t.type == Type.FOR) {
            advance();
            LgsToken var = expect(Type.IDENT, "for 后的变量名");
            expect(Type.IN, "in");
            Expr listExpr = parseExpression();
            expect(Type.DO, "do");
            List<Stmt> body = parseStatements("end");
            expect(Type.END, "end");
            expect(Type.FOR, "for");
            return new ForStmt(var.text, listExpr, body, t.line);
        }

        // try ... catch ... end try
        if (t.type == Type.TRY) {
            advance();
            List<Stmt> tryBody = parseStatements("catch");
            expect(Type.CATCH, "catch");
            List<Stmt> catchBody = parseStatements("end");
            expect(Type.END, "end");
            expect(Type.TRY, "try");
            return new TryStmt(tryBody, catchBody, t.line);
        }

        // mod.模块名(参数...) -> 变量（无参调用可省略括号）
        if (t.type == Type.IDENT && t.text.equals("mod") && peek2().type == Type.DOT) {
            advance(); // mod
            advance(); // .
            LgsToken name = expect(Type.IDENT, "模块名");
            List<Expr> args = new ArrayList<>();
            if (match(Type.LPAREN)) {
                args = parseArgList();
                expect(Type.RPAREN, ")");
            }
            String outVar = parseOptionalOutput();
            return new ModCallStmt(name.text, args, outVar, t.line);
        }

        // jump.步骤名(参数...)
        if (t.type == Type.IDENT && t.text.equals("jump") && peek2().type == Type.DOT) {
            advance(); // jump
            advance(); // .
            LgsToken name = expect(Type.IDENT, "步骤名");
            expect(Type.LPAREN, "(");
            List<Expr> args = parseArgList();
            expect(Type.RPAREN, ")");
            return new JumpStmt(name.text, args, t.line);
        }

        // 指令调用：指令名 参数1, 参数2 -> 变量
        if (t.type == Type.IDENT) {
            advance();
            String instrName = t.text;
            List<Expr> args = new ArrayList<>();
            // 无参数的指令（如 get_difficulty -> diff）
            if (!isStmtEnd() && !check(Type.ARROW)) {
                args.add(parseExpression());
                while (match(Type.COMMA)) {
                    args.add(parseExpression());
                }
            }
            String outVar = parseOptionalOutput();
            return new InstrCallStmt(instrName, args, outVar, t.line);
        }

        throw error(t, "无法解析的语句（以 '" + t.text + "' 开头）");
    }

    // ==================== if 语句 ====================

    private IfStmt parseIf() throws LogicException {
        LgsToken ifTok = advance(); // if
        List<IfStmt.Branch> branches = new ArrayList<>();

        // 第一个 if 分支
        Expr cond = parseExpression();
        expect(Type.THEN, "then");
        List<Stmt> body = parseStatements("end");
        branches.add(new IfStmt.Branch(cond, body));

        // else if 分支
        while (peek().type == Type.ELSE && peek2().type == Type.IF) {
            advance(); // else
            advance(); // if
            Expr elifCond = parseExpression();
            expect(Type.THEN, "then");
            List<Stmt> elifBody = parseStatements("end");
            branches.add(new IfStmt.Branch(elifCond, elifBody));
        }

        // else 分支
        List<Stmt> elseBody = null;
        if (match(Type.ELSE)) {
            elseBody = parseStatements("end");
        }

        expect(Type.END, "end");
        expect(Type.IF, "if");
        return new IfStmt(branches, elseBody, ifTok.line);
    }

    // ==================== 参数 / 输出 ====================

    /** 解析括号内的参数列表（已消费 '('，需在调用前消费）。 */
    private List<Expr> parseArgList() throws LogicException {
        List<Expr> args = new ArrayList<>();
        if (!check(Type.RPAREN)) {
            args.add(parseExpression());
            while (match(Type.COMMA)) {
                args.add(parseExpression());
            }
        }
        return args;
    }

    /** 解析可选的 -> 输出变量。返回变量名或 null。 */
    private String parseOptionalOutput() throws LogicException {
        if (match(Type.ARROW)) {
            return parseScopedName("-> 后的输出变量名");
        }
        return null;
    }

    /**
     * 解析可能带作用域前缀的变量名：name 或 scope.name（如 global.x）。
     * 解释器以完整的 "global.x" 字符串作为变量名，故此处需把点号拼接回去。
     */
    private String parseScopedName(String what) throws LogicException {
        LgsToken first = expect(Type.IDENT, what);
        String name = first.text;
        while (check(Type.DOT)) {
            advance(); // 消费点号
            LgsToken part = expect(Type.IDENT, what + "的成员");
            name = name + "." + part.text;
        }
        return name;
    }

    // ==================== 表达式（优先级爬升） ====================

    /** 表达式入口。 */
    public Expr parseExpression() throws LogicException {
        return parseOr();
    }

    /** or（最低优先级）。 */
    private Expr parseOr() throws LogicException {
        Expr left = parseAnd();
        while (match(Type.OR)) {
            Expr right = parseAnd();
            left = new BinaryExpr("or", left, right, left.line());
        }
        return left;
    }

    /** and。 */
    private Expr parseAnd() throws LogicException {
        Expr left = parseComparison();
        while (match(Type.AND)) {
            Expr right = parseComparison();
            left = new BinaryExpr("and", left, right, left.line());
        }
        return left;
    }

    /** 比较：< > <= >= == !=。 */
    private Expr parseComparison() throws LogicException {
        Expr left = parseAddSub();
        while (true) {
            Type t = peek().type;
            String op = null;
            if (t == Type.LT) op = "<";
            else if (t == Type.GT) op = ">";
            else if (t == Type.LE) op = "<=";
            else if (t == Type.GE) op = ">=";
            else if (t == Type.EQ) op = "==";
            else if (t == Type.NE) op = "!=";
            if (op == null) break;
            advance();
            Expr right = parseAddSub();
            left = new BinaryExpr(op, left, right, left.line());
        }
        return left;
    }

    /** 加减：+ -。 */
    private Expr parseAddSub() throws LogicException {
        Expr left = parseMulDiv();
        while (true) {
            Type t = peek().type;
            if (t == Type.PLUS) {
                advance();
                left = new BinaryExpr("+", left, parseMulDiv(), left.line());
            } else if (t == Type.MINUS) {
                advance();
                left = new BinaryExpr("-", left, parseMulDiv(), left.line());
            } else {
                break;
            }
        }
        return left;
    }

    /** 乘除：* /。 */
    private Expr parseMulDiv() throws LogicException {
        Expr left = parseUnary();
        while (true) {
            Type t = peek().type;
            if (t == Type.STAR) {
                advance();
                left = new BinaryExpr("*", left, parseUnary(), left.line());
            } else if (t == Type.SLASH) {
                advance();
                left = new BinaryExpr("/", left, parseUnary(), left.line());
            } else {
                break;
            }
        }
        return left;
    }

    /** 一元：! -。 */
    private Expr parseUnary() throws LogicException {
        if (match(Type.BANG)) {
            return new UnaryExpr("!", parseUnary(), peek().line);
        }
        if (match(Type.MINUS)) {
            return new UnaryExpr("-", parseUnary(), peek().line);
        }
        return parsePrimary();
    }

    /** 主要表达式：字面量 / 变量 / 括号 / 列表 / 函数调用。 */
    private Expr parsePrimary() throws LogicException {
        LgsToken t = peek();

        // 数字
        if (t.type == Type.NUMBER) {
            advance();
            return new NumExpr(t.num, t.line);
        }
        // 字符串
        if (t.type == Type.STRING) {
            advance();
            return new StrExpr(t.text, t.line);
        }
        // true / false
        if (t.type == Type.TRUE) {
            advance();
            return new BoolExpr(true, t.line);
        }
        if (t.type == Type.FALSE) {
            advance();
            return new BoolExpr(false, t.line);
        }
        // null
        if (t.type == Type.NULL) {
            advance();
            return new NullExpr(t.line);
        }
        // 括号分组
        if (t.type == Type.LPAREN) {
            advance();
            Expr e = parseExpression();
            expect(Type.RPAREN, ")");
            return e;
        }
        // 列表字面量
        if (t.type == Type.LBRACKET) {
            return parseListLiteral();
        }
        // 标识符：变量引用 或 函数调用
        if (t.type == Type.IDENT) {
            advance();
            // 函数调用：name(args)
            if (check(Type.LPAREN)) {
                advance(); // (
                List<Expr> args = parseArgList();
                expect(Type.RPAREN, ")");
                return new CallExpr(t.text, args, t.line);
            }
            // 作用域变量名：name 或 scope.name（如 global.x），解释器按完整字符串处理
            String name = t.text;
            while (check(Type.DOT)) {
                advance(); // 消费点号
                LgsToken part = expect(Type.IDENT, "变量名的成员");
                name = name + "." + part.text;
            }
            return new VarExpr(name, t.line);
        }

        throw error(t, "无法解析的表达式（意外的 Token '" + t.text + "'）");
    }

    /** 列表字面量：[expr, expr, ...]。 */
    private Expr parseListLiteral() throws LogicException {
        LgsToken bracket = advance(); // [
        List<Expr> elements = new ArrayList<>();
        if (!check(Type.RBRACKET)) {
            elements.add(parseExpression());
            while (match(Type.COMMA)) {
                elements.add(parseExpression());
            }
        }
        expect(Type.RBRACKET, "]");
        return new ListExpr(elements, bracket.line);
    }

    // ==================== 辅助方法 ====================

    /** 判断当前是否为语句结束（换行 / EOF / 终止关键字）。 */
    private boolean isStmtEnd() {
        Type t = peek().type;
        return t == Type.EOF || t == Type.END || t == Type.ELSE || t == Type.UNTIL
                || t == Type.CATCH || t == Type.STEP || t == Type.MODULE;
    }

    private boolean isAtEnd() {
        return peek().type == Type.EOF;
    }

    private LgsToken peek() {
        return tokens.get(pos);
    }

    private LgsToken peek2() {
        return pos + 1 < tokens.size() ? tokens.get(pos + 1) : tokens.get(tokens.size() - 1);
    }

    private LgsToken advance() {
        if (!isAtEnd()) pos++;
        return tokens.get(pos - 1);
    }

    private boolean check(Type type) {
        return peek().type == type;
    }

    private boolean match(Type type) {
        if (check(type)) {
            advance();
            return true;
        }
        return false;
    }

    private LgsToken expect(Type type, String what) throws LogicException {
        if (check(type)) return advance();
        throw error(peek(), "期望 " + what + " (" + type + ")，但遇到 '" + peek().text + "' (" + peek().type + ") @" + peek().line);
    }

    private static LogicException error(LgsToken tok, String msg) {
        return new LogicException("[LogicStep] 第 " + tok.line + " 行：" + msg);
    }
}
