package umrao.compile.engine;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom JavaFileManager that routes bytecode output directly to memory buffers
 * rather than the physical filesystem.
 */
public class InMemoryFileManager extends ForwardingJavaFileManager<JavaFileManager> {

    private final Map<String, BytecodeJavaFileObject> bytecodes = new ConcurrentHashMap<>();

    public InMemoryFileManager(JavaFileManager fileManager) {
        super(fileManager);
    }

    @Override
    public JavaFileObject getJavaFileForOutput(Location location,
                                              String className,
                                              JavaFileObject.Kind kind,
                                              FileObject sibling) {
        BytecodeJavaFileObject bytecode = new BytecodeJavaFileObject(className);
        bytecodes.put(className, bytecode);
        return bytecode;
    }

    public Map<String, BytecodeJavaFileObject> getBytecodes() {
        return Collections.unmodifiableMap(bytecodes);
    }

    public byte[] getBytecode(String className) {
        BytecodeJavaFileObject obj = bytecodes.get(className);
        return obj != null ? obj.getByteCode() : null;
    }
}
