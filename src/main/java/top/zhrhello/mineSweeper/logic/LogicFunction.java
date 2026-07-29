package top.zhrhello.mineSweeper.logic;

import java.util.List;

/**
 * 一个 Logic 函数的定义：名称、参数名列表、步骤序列。
 */
public class LogicFunction {
    public final String name;
    public final List<String> parameters;
    public final List<Step> steps;

    public LogicFunction(String name, List<String> parameters, List<Step> steps) {
        this.name = name;
        this.parameters = parameters;
        this.steps = steps;
    }
}
