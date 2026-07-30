package top.zhrhello.mineSweeper.logic.lgs;

import top.zhrhello.mineSweeper.logic.LogicException;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * LogicStep 脚本编译器。
 *
 * 负责从磁盘读取 .lgs 文件，词法分析 → 语法分析 → 生成 {@link LgsScript}。
 * 支持加载单个文件或整个目录。
 *
 * 编译流程：
 * <pre>
 *   .lgs 文件 → {@link LgsLexer} → Token 列表 → {@link LgsParser} → AST → {@link LgsScript}
 * </pre>
 *
 * 编译时进行基本静态检查：
 * <ul>
 *   <li>语法正确性（括号匹配、end 闭合等）</li>
 *   <li>步骤/模块名不重复</li>
 * </ul>
 *
 * 运行时检查（由 {@link LgsInterpreter} 执行）：
 * <ul>
 *   <li>变量未定义、索引越界、除零等</li>
 *   <li>步数/深度/循环迭代超限</li>
 * </ul>
 */
public final class LgsCompiler {

    private LgsCompiler() {}

    /**
     * 从源码字符串编译脚本。
     *
     * @param source LogicStep 源码
     * @param fileName 文件名（仅用于错误提示）
     * @return 编译后的 {@link LgsScript}
     * @throws LogicException 编译错误（语法错误、重复定义等）
     */
    public static LgsScript compile(String source, String fileName) throws LogicException {
        LgsLexer lexer = new LgsLexer(source);
        List<LgsToken> tokens = lexer.tokenize();

        LgsParser parser = new LgsParser(tokens);
        List<Object> defs = parser.parseScript();

        LgsScript script = new LgsScript();
        for (Object def : defs) {
            if (def instanceof LgsAst.StepDef step) {
                script.addStep(step);
            } else if (def instanceof LgsAst.ModuleDef mod) {
                script.addModule(mod);
            }
        }
        return script;
    }

    /**
     * 从文件编译脚本。
     *
     * @param file .lgs 文件
     * @return 编译后的 {@link LgsScript}
     * @throws LogicException 编译错误或文件读取错误
     */
    public static LgsScript compileFile(File file) throws LogicException {
        String source;
        try {
            source = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new LogicException("[LogicStep] 无法读取脚本文件 " + file.getName() + ": " + e.getMessage());
        }
        try {
            return compile(source, file.getName());
        } catch (LogicException e) {
            throw new LogicException("[LogicStep] 文件 " + file.getName() + " 编译失败: " + e.getMessage());
        }
    }

    /**
     * 从目录加载所有 .lgs 文件并合并为一个脚本。
     * 如果目录不存在或无 .lgs 文件，返回空脚本。
     *
     * @param dir 脚本目录
     * @return 合并后的 {@link LgsScript}
     * @throws LogicException 任一文件编译错误
     */
    public static LgsScript compileDirectory(File dir) throws LogicException {
        LgsScript merged = new LgsScript();
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return merged;
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".lgs"));
        if (files == null || files.length == 0) {
            return merged;
        }
        // 按文件名排序，保证加载顺序确定
        List<File> sorted = new ArrayList<>(List.of(files));
        sorted.sort(File::compareTo);

        for (File f : sorted) {
            LgsScript s = compileFile(f);
            try {
                merged.merge(s);
            } catch (LogicException e) {
                throw new LogicException("[LogicStep] 合并脚本 " + f.getName() + " 失败: " + e.getMessage());
            }
        }
        return merged;
    }
}
