package top.zhrhello.mineSweeper.logic;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 基于递归下降的表达式引擎。
 *
 * 设计要点：
 * 1. 表达式在解析前已由调用方完成 {@code {变量}} 占位符替换 —— 替换后的文本只含
 *    数字、字符串字面量、运算符、括号、函数调用与关键字。
 * 2. 支持算术 ( + - * / % )、字符串拼接 ( + )、比较 ( < > <= >= == !=，返回 1/0 )、
 *    逻辑 ( and or not，返回 1/0 )、字符串关系运算 ( contains / starts_with /
 *    ends_with / matches / in，返回 1/0 )、三元运算符 ( ? : )、函数调用
 *    ( name(args) ) 与括号优先级。
 * 3. 内置大量函数（数学 abs/floor/ceil/max/min/pow…、类型判断 is_number/type_of、
 *    类型转换 to_number、字符串 upper/lower/trim/index_of/split/join、日期
 *    now/date/date_diff/date_format/date_parse 以及三元 if、随机 pick、序列 range 等），
 *    详见 {@link #callFunction}。
 * 4. 每次 eval 最多运行 {@code max_eval_ms}（默认 50ms），超限抛出 LogicException（超时）。
 *
 * 求值结果统一为 Object：Double 表示数字（含布尔的 1.0/0.0），String 表示字符串，
 * List/Map 用于 split/range 等返回集合的函数。
 */
public final class Expression {

    // 当前求值截止时间（纳秒）。Logic 解释器运行在主线程，单线程执行，使用 ThreadLocal 足够。
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();

    private Expression() {
    }

    /** 解析并执行表达式，返回 Double 或 String。expr 为 null 时返回空串。 */
    public static Object evaluate(String expr, long deadlineNanos) throws LogicException {
        if (expr == null) return "";
        Long prev = DEADLINE.get();
        DEADLINE.set(deadlineNanos);
        try {
            Tokenizer tk = new Tokenizer(expr);
            List<Token> tokens = tk.tokenize();
            if (tokens.isEmpty()) return "";
            Parser parser = new Parser(tokens);
            Node node = parser.parseExpression();
            return node.eval();
        } finally {
            if (prev != null) DEADLINE.set(prev);
            else DEADLINE.remove();
        }
    }

    // ---------- 词法 ----------
    enum TokenType { NUMBER, STRING, OP, LPAREN, RPAREN, AND, OR, NOT, WORD, COMMA, QMARK, COLON, EOF }

    static class Token {
        final TokenType type;
        final String text;
        final double num;

        Token(TokenType type, String text) {
            this.type = type;
            this.text = text;
            this.num = 0;
        }

        Token(TokenType type, double num) {
            this.type = type;
            this.text = null;
            this.num = num;
        }
    }

    static class Tokenizer {
        private final String s;
        private int i;

        Tokenizer(String s) {
            this.s = s;
        }

        List<Token> tokenize() throws LogicException {
            List<Token> out = new ArrayList<>();
            int n = s.length();
            while (i < n) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                if (c == '"') {
                    i++;
                    StringBuilder sb = new StringBuilder();
                    while (i < n) {
                        char d = s.charAt(i);
                        if (d == '"') {
                            i++;
                            break;
                        }
                        if (d == '\\' && i + 1 < n) {
                            i++;
                            sb.append(s.charAt(i));
                            i++;
                        } else {
                            sb.append(d);
                            i++;
                        }
                    }
                    out.add(new Token(TokenType.STRING, sb.toString()));
                    continue;
                }
                if (Character.isDigit(c) || (c == '-' && i + 1 < n
                        && Character.isDigit(s.charAt(i + 1)) && isOpOrParenPrev(i))) {
                    int start = i;
                    i++;
                    while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
                    out.add(new Token(TokenType.NUMBER, Double.parseDouble(s.substring(start, i))));
                    continue;
                }
                if (c == '(') {
                    out.add(new Token(TokenType.LPAREN, "("));
                    i++;
                    continue;
                }
                if (c == ')') {
                    out.add(new Token(TokenType.RPAREN, ")"));
                    i++;
                    continue;
                }
                if (c == ',') {
                    out.add(new Token(TokenType.COMMA, ","));
                    i++;
                    continue;
                }
                if (c == '?') {
                    out.add(new Token(TokenType.QMARK, "?"));
                    i++;
                    continue;
                }
                if (c == ':') {
                    out.add(new Token(TokenType.COLON, ":"));
                    i++;
                    continue;
                }
                if (i + 1 < n) {
                    String two = s.substring(i, i + 2);
                    if (two.equals("<=") || two.equals(">=") || two.equals("==") || two.equals("!=")) {
                        out.add(new Token(TokenType.OP, two));
                        i += 2;
                        continue;
                    }
                }
                if (c == '+' || c == '-' || c == '*' || c == '/' || c == '%' || c == '<' || c == '>') {
                    out.add(new Token(TokenType.OP, String.valueOf(c)));
                    i++;
                    continue;
                }
                if (Character.isLetter(c)) {
                    int start = i;
                    while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
                    String w = s.substring(start, i);
                    if (w.equals("and")) out.add(new Token(TokenType.AND, "and"));
                    else if (w.equals("or")) out.add(new Token(TokenType.OR, "or"));
                    else if (w.equals("not")) out.add(new Token(TokenType.NOT, "not"));
                    else out.add(new Token(TokenType.WORD, w));   // 函数名或关键字运算符（contains 等）
                    continue;
                }
                throw new LogicException("表达式中存在无法识别的字符: '" + c + "' (位置 " + i + ")");
            }
            out.add(new Token(TokenType.EOF, ""));
            return out;
        }

        private boolean isOpOrParenPrev(int idx) {
            if (idx == 0) return true;
            char p = s.charAt(idx - 1);
            return p == '(' || p == '+' || p == '-' || p == '*' || p == '/' || p == '%'
                    || p == '<' || p == '>' || p == '=' || p == '!' || p == ',' || p == ':';
        }
    }

    // ---------- 语法（优先级爬升） ----------
    static class Parser {
        private final List<Token> tokens;
        private int pos;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        private Token peek() {
            return tokens.get(pos);
        }

        private Token next() {
            return tokens.get(pos++);
        }

        private void expect(TokenType t) throws LogicException {
            if (peek().type != t) {
                throw new LogicException("表达式语法错误：期望 " + t + "，但遇到 '" + peek().text + "'");
            }
            next();
        }

        private void checkTime() throws LogicException {
            Long d = DEADLINE.get();
            if (d != null && System.nanoTime() > d) {
                throw new LogicException("表达式执行超时（超过限制）");
            }
        }

        private boolean isWordOp(String w) {
            return w.equals("contains") || w.equals("starts_with")
                    || w.equals("ends_with") || w.equals("matches") || w.equals("in");
        }

        private Token peek2() {
            return (pos + 1 < tokens.size()) ? tokens.get(pos + 1) : new Token(TokenType.EOF, "");
        }

        Node parseExpression() throws LogicException {
            return parseTernary();
        }

        /** 三元运算符：cond ? a : b（右结合）。 */
        private Node parseTernary() throws LogicException {
            checkTime();
            Node cond = parseOr();
            if (peek().type == TokenType.QMARK) {
                next();
                checkTime();
                Node t = parseExpression();
                expect(TokenType.COLON);
                Node f = parseExpression();
                return new TernaryNode(cond, t, f);
            }
            return cond;
        }

        private Node parseOr() throws LogicException {
            checkTime();
            Node left = parseAnd();
            while (peek().type == TokenType.OR) {
                next();
                checkTime();
                left = new BinNode("or", left, parseAnd());
            }
            return left;
        }

        private Node parseAnd() throws LogicException {
            checkTime();
            Node left = parseNot();
            while (peek().type == TokenType.AND) {
                next();
                checkTime();
                left = new BinNode("and", left, parseNot());
            }
            return left;
        }

        private Node parseNot() throws LogicException {
            checkTime();
            if (peek().type == TokenType.NOT) {
                next();
                return new UnNode("not", parseNot());
            }
            return parseComparison();
        }

        private Node parseComparison() throws LogicException {
            checkTime();
            Node left = parseAdd();
            while (true) {
                Token t = peek();
                if (t.type == TokenType.OP && isCompare(t.text)) {
                    String op = next().text;
                    checkTime();
                    left = new BinNode(op, left, parseAdd());
                } else if (t.type == TokenType.WORD && isWordOp(t.text)) {
                    String op = next().text;
                    checkTime();
                    left = new WordOpNode(op, left, parseAdd());
                } else {
                    break;
                }
            }
            return left;
        }

        private Node parseAdd() throws LogicException {
            checkTime();
            Node left = parseMul();
            while (peek().type == TokenType.OP && (peek().text.equals("+") || peek().text.equals("-"))) {
                String op = next().text;
                checkTime();
                left = new BinNode(op, left, parseMul());
            }
            return left;
        }

        private Node parseMul() throws LogicException {
            checkTime();
            Node left = parseUnary();
            while (peek().type == TokenType.OP
                    && (peek().text.equals("*") || peek().text.equals("/") || peek().text.equals("%"))) {
                String op = next().text;
                checkTime();
                left = new BinNode(op, left, parseUnary());
            }
            return left;
        }

        private Node parseUnary() throws LogicException {
            checkTime();
            if (peek().type == TokenType.OP && peek().text.equals("-")) {
                next();
                return new UnNode("-", parseUnary());
            }
            return parsePrimary();
        }

        private Node parsePrimary() throws LogicException {
            checkTime();
            Token t = peek();
            if (t.type == TokenType.NUMBER) {
                next();
                return new NumNode(t.num);
            }
            if (t.type == TokenType.STRING) {
                next();
                return new StrNode(t.text);
            }
            if (t.type == TokenType.LPAREN) {
                next();
                Node e = parseExpression();
                expect(TokenType.RPAREN);
                return e;
            }
            if (t.type == TokenType.WORD) {
                // 函数调用：name ( args )
                if (peek2().type == TokenType.LPAREN) {
                    String name = next().text;
                    expect(TokenType.LPAREN);
                    List<Node> args = new ArrayList<>();
                    if (peek().type != TokenType.RPAREN) {
                        args.add(parseExpression());
                        while (peek().type == TokenType.COMMA) {
                            next();
                            args.add(parseExpression());
                        }
                    }
                    expect(TokenType.RPAREN);
                    return new CallNode(name, args);
                }
                throw new LogicException("表达式中存在无法识别的标识符: " + t.text);
            }
            throw new LogicException("表达式语法错误：意外的令牌 '" + t.text + "'");
        }

        private boolean isCompare(String op) {
            return op.equals("<") || op.equals(">") || op.equals("<=") || op.equals(">=")
                    || op.equals("==") || op.equals("!=");
        }
    }

    // ---------- AST 节点 ----------
    interface Node {
        Object eval() throws LogicException;
    }

    static class NumNode implements Node {
        private final double v;

        NumNode(double v) {
            this.v = v;
        }

        public Object eval() {
            return v;
        }
    }

    static class StrNode implements Node {
        private final String v;

        StrNode(String v) {
            this.v = v;
        }

        public Object eval() {
            return v;
        }
    }

    static class UnNode implements Node {
        private final String op;
        private final Node child;

        UnNode(String op, Node child) {
            this.op = op;
            this.child = child;
        }

        public Object eval() throws LogicException {
            if (op.equals("not")) {
                return isTruthy(child.eval()) ? 0.0 : 1.0;
            }
            if (op.equals("-")) {
                return -toDouble(child.eval());
            }
            throw new LogicException("未知一元运算符: " + op);
        }
    }

    static class BinNode implements Node {
        private final String op;
        private final Node left;
        private final Node right;

        BinNode(String op, Node left, Node right) {
            this.op = op;
            this.left = left;
            this.right = right;
        }

        public Object eval() throws LogicException {
            checkTime();
            if (op.equals("and")) {
                return (isTruthy(left.eval()) && isTruthy(right.eval())) ? 1.0 : 0.0;
            }
            if (op.equals("or")) {
                return (isTruthy(left.eval()) || isTruthy(right.eval())) ? 1.0 : 0.0;
            }
            Object lv = left.eval();
            Object rv = right.eval();
            if (op.equals("+")) {
                if (lv instanceof String || rv instanceof String) {
                    return toStr(lv) + toStr(rv);
                }
                return toDouble(lv) + toDouble(rv);
            }
            if (op.equals("-")) return toDouble(lv) - toDouble(rv);
            if (op.equals("*")) return toDouble(lv) * toDouble(rv);
            if (op.equals("/")) {
                double d = toDouble(rv);
                if (d == 0.0) throw new LogicException("除零错误");
                return toDouble(lv) / d;
            }
            if (op.equals("%")) {
                double d = toDouble(rv);
                if (d == 0.0) throw new LogicException("取模除零错误");
                return toDouble(lv) % d;
            }
            if (op.equals("==")) return equalsVal(lv, rv) ? 1.0 : 0.0;
            if (op.equals("!=")) return equalsVal(lv, rv) ? 0.0 : 1.0;
            if (op.equals("<") || op.equals(">") || op.equals("<=") || op.equals(">=")) {
                int cmp = compareVal(lv, rv);
                switch (op) {
                    case "<":  return cmp < 0 ? 1.0 : 0.0;
                    case ">":  return cmp > 0 ? 1.0 : 0.0;
                    case "<=": return cmp <= 0 ? 1.0 : 0.0;
                    case ">=": return cmp >= 0 ? 1.0 : 0.0;
                }
            }
            throw new LogicException("未知运算符: " + op);
        }

        private void checkTime() throws LogicException {
            Long d = DEADLINE.get();
            if (d != null && System.nanoTime() > d) {
                throw new LogicException("表达式执行超时（超过限制）");
            }
        }
    }

    // 关键字运算符（字符串关系）：contains / starts_with / ends_with / matches / in
    static class WordOpNode implements Node {
        private final String op;
        private final Node left;
        private final Node right;

        WordOpNode(String op, Node left, Node right) {
            this.op = op;
            this.left = left;
            this.right = right;
        }

        public Object eval() throws LogicException {
            checkTime();
            String l = toStr(left.eval());
            String r = toStr(right.eval());
            boolean result;
            switch (op) {
                case "contains":     result = l.contains(r); break;
                case "starts_with":  result = l.startsWith(r); break;
                case "ends_with":    result = l.endsWith(r); break;
                case "matches":      result = l.matches(r); break;
                case "in":           result = r.contains(l); break;   // "x in y" = y 包含 x
                default: throw new LogicException("未知运算符: " + op);
            }
            return result ? 1.0 : 0.0;
        }
    }

    // 函数调用：name(args)
    static class CallNode implements Node {
        private final String name;
        private final List<Node> args;

        CallNode(String name, List<Node> args) {
            this.name = name;
            this.args = args;
        }

        public Object eval() throws LogicException {
            checkTime();
            List<Object> vals = new ArrayList<>(args.size());
            for (Node a : args) vals.add(a.eval());
            return callFunction(name, vals);
        }
    }

    // 三元运算符：cond ? a : b
    static class TernaryNode implements Node {
        private final Node cond, a, b;

        TernaryNode(Node cond, Node a, Node b) {
            this.cond = cond;
            this.a = a;
            this.b = b;
        }

        public Object eval() throws LogicException {
            checkTime();
            return isTruthy(cond.eval()) ? a.eval() : b.eval();
        }
    }

    // 统一超时检查（供节点复用）
    private static void checkTime() throws LogicException {
        Long d = DEADLINE.get();
        if (d != null && System.nanoTime() > d) {
            throw new LogicException("表达式执行超时（超过限制）");
        }
    }

    /**
     * 内置函数分发。参数已求值；返回 Double（含布尔的 1.0/0.0）、String 或 List。
     * 完整可用函数见 README_CONFIG.md「表达式内置函数」。
     */
    public static Object callFunction(String name, List<Object> args) throws LogicException {
        switch (name) {
            // ---- 数学 ----
            case "abs":   req(args, 1, name); return Math.abs(num(args, 0));
            case "floor": req(args, 1, name); return Math.floor(num(args, 0));
            case "ceil":  req(args, 1, name); return Math.ceil(num(args, 0));
            case "round": req(args, 1, name); return (double) Math.round(num(args, 0));
            case "sqrt":  req(args, 1, name); return Math.sqrt(num(args, 0));
            case "sign":  req(args, 1, name); return Math.signum(num(args, 0));
            case "pow":   req(args, 2, name); return Math.pow(num(args, 0), num(args, 1));
            case "max":   req(args, 2, name); return Math.max(num(args, 0), num(args, 1));
            case "min":   req(args, 2, name); return Math.min(num(args, 0), num(args, 1));
            case "clamp": req(args, 3, name); { double x = num(args, 0), lo = num(args, 1), hi = num(args, 2);
                                                  return x < lo ? lo : (x > hi ? hi : x); }
            case "mod":   req(args, 2, name); { double d = num(args, 1); if (d == 0.0) throw new LogicException("mod 除零错误");
                                                  return num(args, 0) % d; }
            // ---- 类型判断 ----
            case "is_number": req(args, 1, name); return isNumber(args.get(0)) ? 1.0 : 0.0;
            case "is_string": req(args, 1, name); return args.get(0) instanceof String ? 1.0 : 0.0;
            case "is_list":   req(args, 1, name); return args.get(0) instanceof List ? 1.0 : 0.0;
            case "is_map":    req(args, 1, name); return args.get(0) instanceof Map ? 1.0 : 0.0;
            case "is_bool":   req(args, 1, name); return args.get(0) instanceof Boolean ? 1.0 : 0.0;
            case "type_of":   req(args, 1, name); return typeOf(args.get(0));
            // ---- 类型转换 ----
            case "to_number": req(args, 1, name); return toNumberSafe(args.get(0));
            case "to_int":    req(args, 1, name); return Math.floor(toNumberSafe(args.get(0)));
            // ---- 字符串 / 集合 ----
            case "length":     req(args, 1, name); return (double) lengthOf(args.get(0));
            case "upper":      req(args, 1, name); return str(args.get(0)).toUpperCase(Locale.ROOT);
            case "lower":      req(args, 1, name); return str(args.get(0)).toLowerCase(Locale.ROOT);
            case "trim":       req(args, 1, name); return str(args.get(0)).trim();
            case "index_of":   req(args, 2, name); return (double) str(args.get(0)).indexOf(str(args, 1));
            case "contains":   req(args, 2, name); return str(args.get(0)).contains(str(args, 1)) ? 1.0 : 0.0;
            case "starts_with":req(args, 2, name); return str(args.get(0)).startsWith(str(args, 1)) ? 1.0 : 0.0;
            case "ends_with":  req(args, 2, name); return str(args.get(0)).endsWith(str(args, 1)) ? 1.0 : 0.0;
            case "matches":    req(args, 2, name); return str(args.get(0)).matches(str(args, 1)) ? 1.0 : 0.0;
            case "replace":    req(args, 3, name); return str(args.get(0)).replace(str(args, 1), str(args, 2));
            case "substr": {
                reqAtLeast(args, 2, name);
                String s = str(args.get(0));
                int st = (int) num(args, 1);
                int en = args.size() >= 3 ? st + (int) num(args, 2) : s.length();
                if (st < 0) st = 0;
                if (en > s.length()) en = s.length();
                if (st > en) st = en;
                return s.substring(st, en);
            }
            case "split": {
                req(args, 2, name);
                List<Object> r = new ArrayList<>();
                for (String part : str(args.get(0)).split(Pattern.quote(str(args, 1)))) r.add(part);
                return r;
            }
            case "join": {
                req(args, 2, name);
                Object l = args.get(0);
                String sep = str(args.get(1));
                if (l instanceof List) {
                    StringBuilder sb = new StringBuilder();
                    List<?> lst = (List<?>) l;
                    for (int i = 0; i < lst.size(); i++) {
                        if (i > 0) sb.append(sep);
                        sb.append(toStr(lst.get(i)));
                    }
                    return sb.toString();
                }
                return toStr(l);
            }
            // ---- 日期 ----
            case "now": req(args, 0, name); return (double) System.currentTimeMillis();
            case "date": {
                String fmt = args.isEmpty() ? "yyyy-MM-dd" : str(args.get(0));
                try {
                    return new SimpleDateFormat(fmt).format(new Date());
                } catch (Exception e) {
                    return new SimpleDateFormat("yyyy-MM-dd").format(new Date());
                }
            }
            // date_diff(t1, t2, unit)：返回 t2-t1 在指定单位下的差值。
            // unit 取 days/hours/minutes/seconds/millis（支持缩写 d/h/m/s/ms）。
            // days 按"日历天"计算（截断到当天 00:00 后求差），可正确判断跨天，
            // 常用于"每日限额/是否同一天领取"等场景；其余单位按毫秒直接换算，适合冷却时间。
            case "date_diff": {
                req(args, 3, name);
                long t1 = (long) num(args, 0);
                long t2 = (long) num(args, 1);
                String unit = str(args, 2).trim().toLowerCase(Locale.ROOT);
                if (unit.equals("days") || unit.equals("day") || unit.equals("d")) {
                    long dayMs = 24L * 60 * 60 * 1000;
                    return (double) ((truncToDay(t2) - truncToDay(t1)) / dayMs);
                }
                long diff = t2 - t1;
                switch (unit) {
                    case "hours": case "hour": case "h":
                        return diff / 3600000.0;
                    case "minutes": case "minute": case "min": case "m":
                        return diff / 60000.0;
                    case "seconds": case "second": case "sec": case "s":
                        return diff / 1000.0;
                    case "millis": case "milli": case "ms":
                        return (double) diff;
                    default:
                        throw new LogicException("date_diff 未知时间单位: " + unit
                                + "（可用 days/hours/minutes/seconds/millis）");
                }
            }
            // date_format(ts, fmt)：把毫秒时间戳 ts 按 fmt 格式化为字符串。
            case "date_format": {
                req(args, 2, name);
                long ts = (long) num(args, 0);
                String fmt = str(args, 1);
                try {
                    return new SimpleDateFormat(fmt).format(new Date(ts));
                } catch (Exception e) {
                    return new SimpleDateFormat("yyyy-MM-dd").format(new Date(ts));
                }
            }
            // date_parse(s, fmt)：把日期字符串 s 按 fmt 解析为毫秒时间戳，失败抛错。
            case "date_parse": {
                req(args, 2, name);
                String s = str(args, 0);
                String fmt = str(args, 1);
                try {
                    return (double) new SimpleDateFormat(fmt).parse(s).getTime();
                } catch (Exception e) {
                    throw new LogicException("date_parse 解析失败: \"" + s + "\"（格式 " + fmt + "）");
                }
            }
            // ---- 其它 ----
            case "if":   req(args, 3, name); return isTruthy(args.get(0)) ? args.get(1) : args.get(2);
            case "pick": req(args, 1, name); {
                Object l = args.get(0);
                if (l instanceof List && !((List<?>) l).isEmpty()) {
                    List<?> lst = (List<?>) l;
                    return lst.get(ThreadLocalRandom.current().nextInt(lst.size()));
                }
                return "";
            }
            case "range": req(args, 2, name); {
                int a = (int) num(args, 0), b = (int) num(args, 1);
                int lo = Math.min(a, b), hi = Math.max(a, b);
                List<Object> r = new ArrayList<>();
                for (int k = lo; k < hi; k++) r.add((double) k);
                return r;
            }
            default:
                throw new LogicException("表达式中存在未知的函数: " + name);
        }
    }

    private static void req(List<Object> args, int n, String name) throws LogicException {
        if (args.size() != n) throw new LogicException("函数 " + name + " 需要 " + n + " 个参数，实际 " + args.size() + " 个");
    }

    private static void reqAtLeast(List<Object> args, int n, String name) throws LogicException {
        if (args.size() < n) throw new LogicException("函数 " + name + " 至少需要 " + n + " 个参数，实际 " + args.size() + " 个");
    }

    private static double num(List<Object> args, int i) throws LogicException {
        return toDouble(args.get(i));
    }

    private static String str(Object o) {
        return toStr(o);
    }

    private static String str(List<Object> args, int i) {
        return toStr(args.get(i));
    }

    private static boolean isNumber(Object o) {
        if (o instanceof Number) return true;
        if (o instanceof String) {
            String t = ((String) o).trim();
            if (t.isEmpty()) return false;
            try {
                Double.parseDouble(t);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private static String typeOf(Object o) {
        if (o == null) return "null";
        if (o instanceof Number) return "number";
        if (o instanceof String) return "string";
        if (o instanceof Boolean) return "boolean";
        if (o instanceof List) return "list";
        if (o instanceof Map) return "map";
        return o.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    private static double toNumberSafe(Object o) {
        try {
            return toDouble(o);
        } catch (LogicException e) {
            return 0.0;
        }
    }

    private static int lengthOf(Object o) {
        if (o instanceof String) return ((String) o).length();
        if (o instanceof List) return ((List<?>) o).size();
        if (o instanceof Map) return ((Map<?, ?>) o).size();
        return 0;
    }

    /** 把毫秒时间戳截断到当天 00:00:00.000，返回截断后的毫秒时间戳（用于 date_diff 的 days 单位）。 */
    private static long truncToDay(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    // ---------- 通用转换工具 ----------
    public static double toDouble(Object o) throws LogicException {
        if (o == null) return 0.0;
        if (o instanceof Double) return (Double) o;
        if (o instanceof Integer) return (Integer) o;
        if (o instanceof Long) return (Long) o;
        if (o instanceof Boolean) return ((Boolean) o) ? 1.0 : 0.0;
        if (o instanceof String) {
            String t = ((String) o).trim();
            if (t.isEmpty()) return 0.0;
            try {
                return Double.parseDouble(t);
            } catch (NumberFormatException e) {
                throw new LogicException("无法将 \"" + o + "\" 转换为数字");
            }
        }
        throw new LogicException("无法将 " + o.getClass().getSimpleName() + " 转换为数字");
    }

    public static String toStr(Object o) {
        if (o == null) return "";
        if (o instanceof Double) {
            double d = (Double) o;
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return Long.toString((long) d);
            }
            return Double.toString(d);
        }
        if (o instanceof Integer || o instanceof Long) return o.toString();
        if (o instanceof Boolean) return ((Boolean) o) ? "1" : "0";
        if (o instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            List<?> list = (List<?>) o;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(toStr(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        if (o instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) sb.append(",");
                sb.append(toStr(e.getKey())).append(":").append(toStr(e.getValue()));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
        return o.toString();
    }

    public static boolean isTruthy(Object o) {
        if (o == null) return false;
        if (o instanceof Double) return ((Double) o) != 0.0;
        if (o instanceof Integer) return ((Integer) o) != 0;
        if (o instanceof Long) return ((Long) o) != 0;
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof String) {
            String s = (String) o;
            return !s.isEmpty() && !s.equals("0") && !s.equalsIgnoreCase("false");
        }
        return true;
    }

    private static boolean equalsVal(Object a, Object b) {
        if (a instanceof Number || b instanceof Number) {
            try {
                return toDouble(a) == toDouble(b);
            } catch (Exception e) {
                return false;
            }
        }
        return toStr(a).equals(toStr(b));
    }

    private static int compareVal(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(toDouble(a), toDouble(b));
        }
        return toStr(a).compareTo(toStr(b));
    }
}
