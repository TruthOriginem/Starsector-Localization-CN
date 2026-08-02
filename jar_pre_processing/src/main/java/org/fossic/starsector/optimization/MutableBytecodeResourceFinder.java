package org.fossic.starsector.optimization;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.codehaus.janino.util.ClassFile;
import org.codehaus.janino.util.resource.Resource;
import org.codehaus.janino.util.resource.ResourceFinder;

/** 可在首次解析前一次性安装整代 class bytecode 的只读 finder。 */
final class MutableBytecodeResourceFinder extends ResourceFinder {
    private volatile Map<String, byte[]> resources = Map.of();

    void install(Map<String, byte[]> classes) {
        LinkedHashMap<String, byte[]> installed = new LinkedHashMap<>();
        classes.forEach((name, bytes) -> installed.put(
                ClassFile.getClassFileResourceName(name), bytes.clone()));
        resources = Map.copyOf(installed);
    }

    @Override
    public Resource findResource(String resourceName) {
        byte[] bytes = resources.get(resourceName);
        if (bytes == null) {
            return null;
        }
        return new Resource() {
            @Override
            public InputStream open() {
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public String getFileName() {
                return resourceName;
            }

            @Override
            public long lastModified() {
                return 0L;
            }
        };
    }
}
