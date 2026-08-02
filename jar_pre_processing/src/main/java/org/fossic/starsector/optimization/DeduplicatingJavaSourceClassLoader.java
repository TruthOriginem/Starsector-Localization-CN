package org.fossic.starsector.optimization;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.codehaus.commons.compiler.CompileException;
import org.codehaus.janino.ClassLoaderIClassLoader;
import org.codehaus.janino.Descriptor;
import org.codehaus.janino.JavaSourceClassLoader;
import org.codehaus.janino.JavaSourceIClassLoader;
import org.codehaus.janino.UnitCompiler;
import org.codehaus.janino.util.ClassFile;
import org.codehaus.janino.util.resource.ResourceFinder;

/**
 * 回移 Janino 官方 compilation-unit 去重修复的 source loader。
 *
 * <p>游戏自带 Janino 2.7.8，把 {@code compiledUnitCompilers} 建成
 * {@code generateBytecodes()} 的局部集合，导致加载第二个及后续 class 时重新编译此前的
 * compilation unit。Janino 3.0.12 将其列为 major bug；本类只把该集合提升到 loader
 * 实例生命周期，不改变 source finder、parent classloader、编码或 bytecode 生成器。
 */
public class DeduplicatingJavaSourceClassLoader
        extends JavaSourceClassLoader {
    private final JavaSourceIClassLoader sourceIClassLoader;
    private final Set<UnitCompiler> compiledUnitCompilers = new HashSet<>();

    private boolean debugSource = Boolean.getBoolean(
            "org.codehaus.janino.source_debugging.enable");
    private boolean debugLines = debugSource;
    private boolean debugVars = debugSource;

    public DeduplicatingJavaSourceClassLoader(
            ClassLoader parentClassLoader,
            ResourceFinder sourceFinder,
            String characterEncoding) {
        this(parentClassLoader, new JavaSourceIClassLoader(
                sourceFinder,
                characterEncoding,
                new ClassLoaderIClassLoader(parentClassLoader)));
    }

    protected DeduplicatingJavaSourceClassLoader(
            ClassLoader parentClassLoader,
            JavaSourceIClassLoader sourceIClassLoader) {
        super(parentClassLoader, sourceIClassLoader);
        this.sourceIClassLoader = sourceIClassLoader;
    }

    protected final boolean debugSource() {
        return debugSource;
    }

    protected final boolean debugLines() {
        return debugLines;
    }

    protected final boolean debugVars() {
        return debugVars;
    }

    @Override
    public void setDebuggingInfo(
            boolean debugSource, boolean debugLines, boolean debugVars) {
        super.setDebuggingInfo(debugSource, debugLines, debugVars);
        this.debugSource = debugSource;
        this.debugLines = debugLines;
        this.debugVars = debugVars;
    }

    @Override
    protected Map<String, byte[]> generateBytecodes(String className)
            throws ClassNotFoundException {
        if (sourceIClassLoader.loadIClass(
                Descriptor.fromClassName(className)) == null) {
            return null;
        }

        Map<String, byte[]> bytecodes = new HashMap<>();
        compileUnits:
        for (;;) {
            for (UnitCompiler unitCompiler
                    : sourceIClassLoader.getUnitCompilers()) {
                if (compiledUnitCompilers.contains(unitCompiler)) {
                    continue;
                }

                ClassFile[] classFiles;
                try {
                    classFiles = unitCompiler.compileUnit(
                            debugSource, debugLines, debugVars);
                } catch (CompileException exception) {
                    throw new ClassNotFoundException(
                            exception.getMessage(), exception);
                }
                for (ClassFile classFile : classFiles) {
                    bytecodes.put(
                            classFile.getThisClassName(),
                            classFile.toByteArray());
                }
                compiledUnitCompilers.add(unitCompiler);
                continue compileUnits;
            }
            return bytecodes;
        }
    }

    Map<String, byte[]> generateForTests(String className)
            throws ClassNotFoundException {
        return generateBytecodes(className);
    }
}
