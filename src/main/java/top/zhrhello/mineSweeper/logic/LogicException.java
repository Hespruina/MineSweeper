package top.zhrhello.mineSweeper.logic;

/**
 * Logic 解释器在运行时抛出的受检式异常（继承自 RuntimeException 以方便向上传播）。
 * 包含类型错误、除零、未定义变量、步数/深度超限、表达式超时等。
 */
public class LogicException extends RuntimeException {
    public LogicException(String message) {
        super(message);
    }

    public LogicException(String message, Throwable cause) {
        super(message, cause);
    }
}
