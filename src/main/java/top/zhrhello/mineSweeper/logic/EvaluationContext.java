package top.zhrhello.mineSweeper.logic;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Logic 求值上下文。在每次顶层调用时创建，并在嵌套 call 间共享：
 * - scopeStack: 函数调用作用域栈（局部/参数变量）
 * - globals: 全局变量表（"global." 前缀），跨函数、跨调用持久（存储于 LogicEngine 中共享）
 * - persistence / plugin / gameContext 等运行时依赖
 * - stepCount: 累计执行步数（用于 max_steps 安全限制）
 * - returnValue: 最近的 return 结果
 *
 * 作用域查找规则：局部变量从栈顶向下搜索；"global." 前缀直接访问 globals。
 */
public class EvaluationContext {
    final Deque<Map<String, Object>> scopeStack = new ArrayDeque<>();
    final Map<String, Object> globals;       // 由 LogicEngine 注入（跨调用共享，运行时可变）
    final Map<String, Object> constants;     // 由 ConfigManager 注入（logic.constants，只读，键不带 "global." 前缀）
    final Persistence persistence;
    final org.bukkit.plugin.java.JavaPlugin plugin;
    final AtomicInteger stepCount = new AtomicInteger(0);
    final int maxSteps;
    Object returnValue = "";

    public EvaluationContext(Map<String, Object> globals, Map<String, Object> constants,
                             Persistence persistence, org.bukkit.plugin.java.JavaPlugin plugin, int maxSteps) {
        this.globals = globals;
        this.constants = constants;
        this.persistence = persistence;
        this.plugin = plugin;
        this.maxSteps = maxSteps;
    }

    void pushScope(Map<String, Object> scope) {
        scopeStack.push(scope);
    }

    void popScope() {
        if (!scopeStack.isEmpty()) scopeStack.pop();
    }

    int depth() {
        return scopeStack.size();
    }

    /** 查找变量（含 global. 前缀处理）。未定义返回 null。
     *  "global.xxx" 优先查运行时全局表，未命中再查只读常量（logic.constants）。 */
    Object lookupRaw(String name) {
        if (name.startsWith("global.")) {
            if (globals.containsKey(name)) return globals.get(name);
            return constants.get(name.substring("global.".length()));
        }
        for (Map<String, Object> scope : scopeStack) {
            if (scope.containsKey(name)) {
                return scope.get(name);
            }
        }
        return null;
    }

    boolean exists(String name) {
        if (name.startsWith("global.")) {
            if (globals.containsKey(name)) return true;
            return constants.containsKey(name.substring("global.".length()));
        }
        for (Map<String, Object> scope : scopeStack) {
            if (scope.containsKey(name)) return true;
        }
        return false;
    }

    void assign(String name, Object value) {
        if (name.startsWith("global.")) {
            // 只读常量不可被运行时赋值覆盖
            if (constants.containsKey(name.substring("global.".length()))) {
                plugin.getLogger().warning("[Logic] 忽略对只读常量 " + name + " 的赋值");
                return;
            }
            globals.put(name, value);
            return;
        }
        scopeStack.peek().put(name, value);
    }
}
