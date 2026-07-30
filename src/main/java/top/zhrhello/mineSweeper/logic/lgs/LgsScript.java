package top.zhrhello.mineSweeper.logic.lgs;

import top.zhrhello.mineSweeper.logic.LogicException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编译后的 LogicStep 脚本：持有所有 Step 与 Module 定义。
 *
 * 一个 LgsScript 可由多个 .lgs 文件合并而成（步骤/模块名不可重复）。
 * 由 {@link LgsCompiler} 从源码编译而来，交由 {@link LgsInterpreter} 执行。
 */
public final class LgsScript {

    private final Map<String, LgsAst.StepDef> steps = new LinkedHashMap<>();
    private final Map<String, LgsAst.ModuleDef> modules = new LinkedHashMap<>();

    /** 添加一个步骤定义，名称重复则抛错。 */
    public void addStep(LgsAst.StepDef step) throws LogicException {
        if (steps.containsKey(step.name()) || modules.containsKey(step.name())) {
            throw new LogicException("[LogicStep] 重复定义: " + step.name() + " (第 " + step.line() + " 行)");
        }
        steps.put(step.name(), step);
    }

    /** 添加一个模块定义，名称重复则抛错。 */
    public void addModule(LgsAst.ModuleDef mod) throws LogicException {
        if (steps.containsKey(mod.name()) || modules.containsKey(mod.name())) {
            throw new LogicException("[LogicStep] 重复定义: " + mod.name() + " (第 " + mod.line() + " 行)");
        }
        modules.put(mod.name(), mod);
    }

    public LgsAst.StepDef getStep(String name) {
        return steps.get(name);
    }

    public LgsAst.ModuleDef getModule(String name) {
        return modules.get(name);
    }

    /** 合并另一个脚本的定义到当前脚本。 */
    public void merge(LgsScript other) throws LogicException {
        for (LgsAst.StepDef s : other.steps.values()) addStep(s);
        for (LgsAst.ModuleDef m : other.modules.values()) addModule(m);
    }

    public int stepCount() { return steps.size(); }
    public int moduleCount() { return modules.size(); }

    public List<String> stepNames() { return new ArrayList<>(steps.keySet()); }
    public List<String> moduleNames() { return new ArrayList<>(modules.keySet()); }
}
