package top.zhrhello.mineSweeper.config;

import java.util.List;
import java.util.Map;

/**
 * 一个奖励流程（rewards.win / rewards.lose 中的一项）。
 * - trigger: 条件函数名（"logic:函数名"），返回 0/false/空串则跳过该流程
 * - vars: 动作组内变量绑定（名称 → "logic:函数名" 或字面量）
 * - actions: 要顺序执行的动作列表
 */
public class RewardFlow {
    public final String trigger;            // 可能为 null
    public final Map<String, String> vars;  // 可能为 null
    public final List<RewardAction> actions;

    public RewardFlow(String trigger, Map<String, String> vars, List<RewardAction> actions) {
        this.trigger = trigger;
        this.vars = vars;
        this.actions = actions;
    }
}
