package top.zhrhello.mineSweeper.config;

import java.util.Map;

/**
 * 一个奖励动作（如 console_command / message / give_chest 等）。
 * type 为动作类型，params 为参数字典。
 */
public class RewardAction {
    public final String type;
    public final Map<String, Object> params;

    public RewardAction(String type, Map<String, Object> params) {
        this.type = type;
        this.params = params;
    }

    public String str(String key) {
        Object v = params.get(key);
        return v == null ? "" : String.valueOf(v);
    }
}
