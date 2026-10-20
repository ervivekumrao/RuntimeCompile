package umrao.compile.engine;

import umrao.compile.store.TieredBytecodeRegistry;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom JavaFileManager that routes bytecode output directly to memory buffers
 * and resolves class dependencies dynamically across the tiered storage hierarchy:
 * <b>ClassLoader &gt; Redis &gt; Filesystem &gt; External DB</b>.
 */
@SuppressWarnings("unused")
public class InMemoryFileManager extends ForwardingJavaFileManager<JavaFileManager> {

    private final Map<String, BytecodeJavaFileObject> bytecodes = new ConcurrentHashMap<>();
    private final TieredBytecodeRegistry tieredRegistry;

    public InMemoryFileManager(JavaFileManager fileManager) {
        this(fileManager, new TieredBytecodeRegistry(null, null, null, null));
    }

    public InMemoryFileManager(JavaFileManager fileManager,
                               Map<String, byte[]> bytecodeRepository,
                               ClassLoader classLoader) {
        super(fileManager);
        this.tieredRegistry = new TieredBytecodeRegistry(classLoader, null, null, null);
        if (bytecodeRepository != null) {
            bytecodeRepository.forEach(this.tieredRegistry::save);
        }
    }

    public InMemoryFileManager(JavaFileManager fileManager, TieredBytecodeRegistry tieredRegistry) {
        super(fileManager);
        this.tieredRegistry = tieredRegistry != null
                ? tieredRegistry
                : new TieredBytecodeRegistry(null, null, null, null);
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

    @Override
    public JavaFileObject getJavaFileForInput(Location location,
                                              String className,
                                              JavaFileObject.Kind kind) throws IOException {
        if (kind == JavaFileObject.Kind.CLASS &&
                (location == StandardLocation.CLASS_PATH || location == StandardLocation.PLATFORM_CLASS_PATH)) {

            // 1. Check bytecode currently being compiled in this task
            BytecodeJavaFileObject current = bytecodes.get(className);
            if (current != null && current.getByteCode() != null) {
                return current;
            }

            // 2. Check Tiered Storage: ClassLoader > Redis > Filesystem > DB
            Optional<byte[]> tieredBytecode = tieredRegistry.findBytecode(className);
            if (tieredBytecode.isPresent()) {
                return new BytecodeJavaFileObject(className, tieredBytecode.get());
            }
        }

        return super.getJavaFileForInput(location, className, kind);
    }

    @Override
    public Iterable<JavaFileObject> list(Location location,
                                         String packageName,
                                         Set<JavaFileObject.Kind> kinds,
                                         boolean recurse) throws IOException {
        Iterable<JavaFileObject> standardFiles = super.list(location, packageName, kinds, recurse);

        if (!kinds.contains(JavaFileObject.Kind.CLASS)) {
            return standardFiles;
        }

        if (location != StandardLocation.CLASS_PATH && location != StandardLocation.PLATFORM_CLASS_PATH) {
            return standardFiles;
        }

        List<JavaFileObject> result = new ArrayList<>();
        Set<String> seenBinaryNames = new HashSet<>();

        if (standardFiles != null) {
            for (JavaFileObject fo : standardFiles) {
                result.add(fo);
                try {
                    String bName = super.inferBinaryName(location, fo);
                    if (bName != null) {
                        seenBinaryNames.add(bName);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // Add matching classes discovered across Tiered Storage (ClassLoader > Redis > Filesystem > DB)
        Map<String, byte[]> tieredClasses = tieredRegistry.findClassesInPackage(packageName, recurse);
        for (Map.Entry<String, byte[]> entry : tieredClasses.entrySet()) {
            String className = entry.getKey();
            if (!seenBinaryNames.contains(className)) {
                result.add(new BytecodeJavaFileObject(className, entry.getValue()));
                seenBinaryNames.add(className);
            }
        }

        return result;
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        if (file instanceof BytecodeJavaFileObject bfo) {
            return bfo.getClassName();
        }
        return super.inferBinaryName(location, file);
    }

    @Override
    public boolean hasLocation(Location location) {
        return location == StandardLocation.CLASS_PATH || super.hasLocation(location);
    }

    public Map<String, BytecodeJavaFileObject> getBytecodes() {
        return Collections.unmodifiableMap(bytecodes);
    }

    public byte[] getBytecode(String className) {
        BytecodeJavaFileObject obj = bytecodes.get(className);
        return obj != null ? obj.getByteCode() : null;
    }

    public TieredBytecodeRegistry getTieredRegistry() {
        return tieredRegistry;
    }
}
