package top.zhrhello.mineSweeper.logic.lgs;

/**
 * LogicStep 词法单元（Token）。
 * 每个 Token 记录类型、文本、数值（仅 NUMBER）、所在行号，供解析器使用。
 */
public final class LgsToken {

    public enum Type {
        // 字面量
        NUMBER, STRING,
        // 关键字
        STEP, MODULE, END, IF, THEN, ELSE, WHILE, DO, REPEAT, UNTIL,
        FOR, IN, RETURN, EXIT, SET, TRY, CATCH, AND, OR,
        TRUE, FALSE, NULL,
        // 运算符
        PLUS, MINUS, STAR, SLASH,
        LT, GT, LE, GE, EQ, NE, BANG, ASSIGN, ARROW,
        // 标点
        LPAREN, RPAREN, LBRACKET, RBRACKET, COMMA, DOT,
        // 标识符
        IDENT,
        // 文件结束
        EOF
    }

    public final Type type;
    public final String text;   // 原始文本（标识符名、关键字名、字符串值等）
    public final double num;    // 仅 NUMBER 类型使用
    public final int line;      // 源码行号（从 1 开始）

    public LgsToken(Type type, String text, double num, int line) {
        this.type = type;
        this.text = text;
        this.num = num;
        this.line = line;
    }

    /** 快捷构造：无数值的 Token。 */
    public LgsToken(Type type, String text, int line) {
        this(type, text, 0, line);
    }

    @Override
    public String toString() {
        return type + "('" + text + "')" + (type == Type.NUMBER ? "=" + num : "") + " @" + line;
    }
}
