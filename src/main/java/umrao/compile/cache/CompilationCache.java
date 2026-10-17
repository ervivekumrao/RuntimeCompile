package umrao.compile.cache;

import umrao.compile.api.CompilationResult;

import java.util.Optional;

/**
 * Cache abstraction for compiled classes.
 */
public interface CompilationCache {

    <T> Optional<CompilationResult<T>> get(String key, Class<T> expectedType);

    <T> void put(String key, CompilationResult<T> result);

    void invalidateAll();

    int size();
}
