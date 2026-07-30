package top.zhrhello.mineSweeper.logic.lgs;

import top.zhrhello.mineSweeper.logic.LogicException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LogicStep 词法分析器（Lexer / Tokenizer）。
 *
 * 将 .lgs 源码文本逐字符扫描为 {@link LgsToken} 列表。
 *
 * 支持的词法元素：
 * <ul>
 *   <li>关键字（step / module / end / if / then / else / while / do / repeat /
 *       until / for / in / return / exit / set / try / catch / and / or /
 *       true / false / null）</li>
 *   <li>数字字面量（整数 & 小数，支持负号前缀由解析器处理）</li>
 *   <li>字符串字面量（双引号包裹，支持 \" \\ 转义）</li>
 *   <li>运算符：+ - * / &lt; &gt; &lt;= &gt;= == != ! = -&gt;</li>
 *   <li>标点：( ) [ ] , .</li>
 *   <li>标识符（字母 / 数字 / 下划线 / 中文，不以数字开头）</li>
 *   <li>注释：// 单行，斜杠星 多行</li>
 * </ul>
 *
 * 对缩进不敏感，换行和空白仅用于分隔 Token。
 */
public final class LgsLexer {

    /** 关键字映射表：小写英文 → Token 类型。 */
    private static final Map<String, LgsToken.Type> KEYWORDS = new HashMap<>();

    static {
        KEYWORDS.put("step", LgsToken.Type.STEP);
        KEYWORDS.put("module", LgsToken.Type.MODULE);
        KEYWORDS.put("end", LgsToken.Type.END);
        KEYWORDS.put("if", LgsToken.Type.IF);
        KEYWORDS.put("then", LgsToken.Type.THEN);
        KEYWORDS.put("else", LgsToken.Type.ELSE);
        KEYWORDS.put("while", LgsToken.Type.WHILE);
        KEYWORDS.put("do", LgsToken.Type.DO);
        KEYWORDS.put("repeat", LgsToken.Type.REPEAT);
        KEYWORDS.put("until", LgsToken.Type.UNTIL);
        KEYWORDS.put("for", LgsToken.Type.FOR);
        KEYWORDS.put("in", LgsToken.Type.IN);
        KEYWORDS.put("return", LgsToken.Type.RETURN);
        KEYWORDS.put("exit", LgsToken.Type.EXIT);
        KEYWORDS.put("set", LgsToken.Type.SET);
        KEYWORDS.put("try", LgsToken.Type.TRY);
        KEYWORDS.put("catch", LgsToken.Type.CATCH);
        KEYWORDS.put("and", LgsToken.Type.AND);
        KEYWORDS.put("or", LgsToken.Type.OR);
        KEYWORDS.put("true", LgsToken.Type.TRUE);
        KEYWORDS.put("false", LgsToken.Type.FALSE);
        KEYWORDS.put("null", LgsToken.Type.NULL);
    }

    private final String src;
    private int pos = 0;
    private int line = 1;
    private final List<LgsToken> tokens = new ArrayList<>();

    public LgsLexer(String src) {
        this.src = src;
    }

    /** 执行词法分析，返回 Token 列表（末尾自动追加 EOF）。 */
    public List<LgsToken> tokenize() throws LogicException {
        int n = src.length();
        while (pos < n) {
            char c = src.charAt(pos);

            // 换行
            if (c == '\n') {
                line++;
                pos++;
                continue;
            }
            // 空白（含 \r \t）
            if (c == ' ' || c == '\t' || c == '\r') {
                pos++;
                continue;
            }
            // 单行注释 //
            if (c == '/' && pos + 1 < n && src.charAt(pos + 1) == '/') {
                pos += 2;
                while (pos < n && src.charAt(pos) != '\n') pos++;
                continue;
            }
            // 多行注释 /* ... */
            if (c == '/' && pos + 1 < n && src.charAt(pos + 1) == '*') {
                pos += 2;
                while (pos < n) {
                    char d = src.charAt(pos);
                    if (d == '*' && pos + 1 < n && src.charAt(pos + 1) == '/') {
                        pos += 2;
                        break;
                    }
                    if (d == '\n') line++;
                    pos++;
                }
                continue;
            }
            // 字符串
            if (c == '"') {
                readString();
                continue;
            }
            // 数字
            if (Character.isDigit(c)) {
                readNumber();
                continue;
            }
            // 标识符 / 关键字（含中文）
            if (Character.isLetter(c) || c == '_') {
                readIdentifier();
                continue;
            }
            // 运算符和标点
            readOperator(c);
        }
        tokens.add(new LgsToken(LgsToken.Type.EOF, "", line));
        return tokens;
    }

    // ---------- 字符串 ----------

    private void readString() throws LogicException {
        int startLine = line;
        pos++; // 跳过开头引号
        StringBuilder sb = new StringBuilder();
        int n = src.length();
        while (pos < n) {
            char c = src.charAt(pos);
            if (c == '"') {
                pos++; // 跳过结尾引号
                tokens.add(new LgsToken(LgsToken.Type.STRING, sb.toString(), startLine));
                return;
            }
            if (c == '\\' && pos + 1 < n) {
                pos++;
                char next = src.charAt(pos);
                switch (next) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    default -> sb.append(next); // 未知转义：保留字符
                }
                pos++;
                continue;
            }
            if (c == '\n') line++;
            sb.append(c);
            pos++;
        }
        throw new LogicException("[LogicStep] 第 " + startLine + " 行：字符串未闭合（缺少结束引号 \")");
    }

    // ---------- 数字 ----------

    private void readNumber() {
        int start = pos;
        int n = src.length();
        pos++;
        while (pos < n && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
            pos++;
        }
        String numStr = src.substring(start, pos);
        double val = Double.parseDouble(numStr);
        tokens.add(new LgsToken(LgsToken.Type.NUMBER, numStr, val, line));
    }

    // ---------- 标识符 / 关键字 ----------

    private void readIdentifier() {
        int start = pos;
        int n = src.length();
        pos++;
        while (pos < n && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
            pos++;
        }
        String word = src.substring(start, pos);
        LgsToken.Type kwType = KEYWORDS.get(word);
        if (kwType != null) {
            tokens.add(new LgsToken(kwType, word, line));
        } else {
            tokens.add(new LgsToken(LgsToken.Type.IDENT, word, line));
        }
    }

    // ---------- 运算符 / 标点 ----------

    private void readOperator(char c) throws LogicException {
        int n = src.length();
        // 双字符运算符
        if (pos + 1 < n) {
            String two = src.substring(pos, pos + 2);
            switch (two) {
                case "<=" -> { add(LgsToken.Type.LE, two); return; }
                case ">=" -> { add(LgsToken.Type.GE, two); return; }
                case "==" -> { add(LgsToken.Type.EQ, two); return; }
                case "!=" -> { add(LgsToken.Type.NE, two); return; }
                case "->" -> { add(LgsToken.Type.ARROW, two); return; }
            }
        }
        // 单字符运算符 / 标点
        switch (c) {
            case '+' -> add(LgsToken.Type.PLUS, "+");
            case '-' -> add(LgsToken.Type.MINUS, "-");
            case '*' -> add(LgsToken.Type.STAR, "*");
            case '/' -> add(LgsToken.Type.SLASH, "/");
            case '<' -> add(LgsToken.Type.LT, "<");
            case '>' -> add(LgsToken.Type.GT, ">");
            case '!' -> add(LgsToken.Type.BANG, "!");
            case '=' -> add(LgsToken.Type.ASSIGN, "=");
            case '(' -> add(LgsToken.Type.LPAREN, "(");
            case ')' -> add(LgsToken.Type.RPAREN, ")");
            case '[' -> add(LgsToken.Type.LBRACKET, "[");
            case ']' -> add(LgsToken.Type.RBRACKET, "]");
            case ',' -> add(LgsToken.Type.COMMA, ",");
            case '.' -> add(LgsToken.Type.DOT, ".");
            default -> throw new LogicException(
                    "[LogicStep] 第 " + line + " 行：无法识别的字符 '" + c + "' (ASCII " + (int) c + ")");
        }
    }

    private void add(LgsToken.Type type, String text) {
        tokens.add(new LgsToken(type, text, line));
        // 按 token 文本长度推进，否则双字符运算符（-> == != <= >=）会漏掉第二个字符
        pos += text.length();
    }
}
