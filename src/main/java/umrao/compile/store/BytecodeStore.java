package umrao.compile.store;

import java.util.Map;
import java.util.Optional;

/**
 * Storage provider interface for persisting and retrieving compiled class bytecodes
 * across tiered locations (e.g. In-Memory Cache/Redis, Local Filesystem, External DB).
 */
public interface BytecodeStore {

    /**
     * Identifies the storage tier (e.g., "REDIS", "FILESYSTEM", "DATABASE").
     */
    String getStorageType();

    /**
     * Persists bytecode for a specific fully-qualified class name.
     */
    void save(String className, byte[] bytecode);

    /**
     * Retrieves bytecode for a class if present in this storage tier.
     */
    Optional<byte[]> load(String className);

    /**
     * Checks whether a class exists in this storage tier.
     */
    boolean contains(String className);

    /**
     * Discovers and retrieves all class bytecodes belonging to a given package.
     */
    Map<String, byte[]> loadPackage(String packageName, boolean recurse);

    /**
     * Clears all stored bytecode in this storage tier.
     */
    default void clear() {
    }
}
