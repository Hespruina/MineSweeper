package top.zhrhello.mineSweeper.logic.lgs;

import top.zhrhello.mineSweeper.logic.GameContext;
import top.zhrhello.mineSweeper.logic.LogicException;

import java.util.List;
import java.util.Map;

/**
 * LogicStep 宿主指令绑定接口。
 *
 * LogicStep 脚本本身只包含流程控制与表达式计算，所有与外部世界（游戏 API、
 * 经济系统、持久化存储、消息发送等）的交互都通过"指令"完成。
 *
 * 宿主程序实现此接口，将 LogicStep 指令名映射到具体功能。
 *
 * 指令调用形式：
 * <ul>
 *   <li>无输出：{@code 指令名 参数1, 参数2}</li>
 *   <li>有输出：{@code 指令名 参数1, 参数2 -> 变量}</li>
 * </ul>
 *
 * 返回值规则：
 * <ul>
 *   <li>有输出变量时，返回值赋给该变量</li>
 *   <li>无输出变量时，返回值被丢弃</li>
 *   <li>无返回值的指令返回 null</li>
 * </ul>
 */
public interface LgsHostBinding {

    /**
     * 调用一条宿主指令。
     *
     * @param name  指令名（如 "get_difficulty"、"give_money"、"list_add"）
     * @param args  已求值的参数列表
     * @param ctx   游戏上下文（玩家、难度、平台大小等），可为 null
     * @return 指令的返回值；无返回值时返回 null
     * @throws LogicException 指令执行出错（参数不匹配、运行时错误等）
     */
    Object callInstruction(String name, List<Object> args, GameContext ctx) throws LogicException;

    /**
     * 判断指定名称的指令是否已注册。
     * 用于静态检查和友好错误提示。
     */
    boolean hasInstruction(String name);

    /**
     * 获取所有已注册指令名（用于文档/调试）。
     */
    List<String> instructionNames();

    /**
     * 获取全局变量存储（跨步骤/模块共享，以 "global." 前缀标识）。
     * 返回的 Map 可被解释器直接读写。
     */
    Map<String, Object> getGlobals();

    /**
     * 获取只读常量（由配置文件定义，不可被脚本运行时修改）。
     * 键名不带 "global." 前缀。
     */
    Map<String, Object> getConstants();
}
