package umrao.compile.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Isolated ClassLoader that loads dynamically compiled classes directly from memory buffers.
 */
public class MemoryClassLoader extends ClassLoader {

    private final Map<String, byte[]> bytecodes = new ConcurrentHashMap<>();

    public MemoryClassLoader(ClassLoader parent) {
        super(parent);
    }

    public MemoryClassLoader(ClassLoader parent, Map<String, byte[]> initialBytecodes) {
        super(parent);
        if (initialBytecodes != null) {
            this.bytecodes.putAll(initialBytecodes);
        }
    }

    public void addBytecode(String className, byte[] bytecode) {
        this.bytecodes.put(className, bytecode);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = bytecodes.get(name);
        if (bytes == null) {
            return super.findClass(name);
        }
        return defineClass(name, bytes, 0, bytes.length);
    }
}
