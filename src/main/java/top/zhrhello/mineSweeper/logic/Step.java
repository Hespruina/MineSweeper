package top.zhrhello.mineSweeper.logic;

import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表示一个 Logic 步骤。每个步骤为 (type, params, out) 三元组：
 * - type: 步骤类型（set / eval / if / while / call ...）
 * - params: 参数字典（可能包含已解析的嵌套 List&lt;Step&gt;，如 then/else/do）
 * - out: 捕获输出变量名（对应语法中的 "- step: -> var" 或 "out: var"），可为 null
 */
public class Step {
    public final String type;
    public final Map<String, Object> params;
    public String out;

    public Step(String type, Map<String, Object> params, String out) {
        this.type = type;
        this.params = params;
        this.out = out;
    }

    /**
     * 从单个步骤的 Map.Entry（键=步骤类型，值=原始参数）解析为 Step。
     * 支持两种输出语法：
     *   - 值字符串以 "-> " 开头（如 "- get_player: -> var"）
     *   - 值为 Map 且含 "out"（或 "-&gt;"）键
     */
    @SuppressWarnings("unchecked")
    public static Step parse(Map.Entry<String, Object> entry) {
        String type = entry.getKey();
        Object raw = entry.getValue();
        if (raw == null) {
            return new Step(type, new LinkedHashMap<>(), null);
        }
        if (raw instanceof Map) {
            Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) raw);
            String out = null;
            if (m.containsKey("out")) {
                out = String.valueOf(m.get("out"));
                m.remove("out");
            } else if (m.containsKey("->")) {
                out = String.valueOf(m.get("->"));
                m.remove("->");
            }
            return new Step(type, m, out);
        }
        if (raw instanceof String) {
            String s = (String) raw;
            String trimmed = s.trim();
            if (trimmed.startsWith("->")) {
                String out = trimmed.substring(2).trim();
                if (out.isEmpty()) {
                    throw new IllegalArgumentException("步骤 " + type + " 的输出变量名为空");
                }
                return new Step(type, new LinkedHashMap<>(), out);
            }
            // 普通字符串值（如 return 的表达式、或 call 的函数名）
            Map<String, Object> m = new LinkedHashMap<>();
            if (type.equals("call")) {
                m.put("function", s);
            } else {
                m.put("value", s);
            }
            return new Step(type, m, null);
        }
        // 其它字面量类型（数字等），视为 value
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", raw);
        return new Step(type, m, null);
    }

    /** 便捷判断：参数中是否包含某个键。 */
    public boolean has(String key) {
        return params.containsKey(key);
    }

    /** 便捷获取字符串参数。 */
    public String str(String key) {
        Object v = params.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    public List<Step> steps(String key) {
        Object v = params.get(key);
        return (v instanceof List) ? (List<Step>) v : null;
    }
}
