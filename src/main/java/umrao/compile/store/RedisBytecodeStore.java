package umrao.compile.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists and resolves compiled class bytecodes in an in-memory distributed cache (Redis)
 * for sub-millisecond cluster-wide sharing across pods.
 */
public class RedisBytecodeStore implements BytecodeStore {

    private static final Logger log = LoggerFactory.getLogger(RedisBytecodeStore.class);
    private static final String DEFAULT_PREFIX = "runtime:bytecode:";

    private final JedisPool jedisPool;
    private final String keyPrefix;
    private final boolean inMemoryMode;
    private final Map<String, byte[]> mockCache;

    public RedisBytecodeStore(JedisPool jedisPool) {
        this(jedisPool, DEFAULT_PREFIX);
    }

    public RedisBytecodeStore(JedisPool jedisPool, String keyPrefix) {
        this.jedisPool = jedisPool;
        this.keyPrefix = keyPrefix != null ? keyPrefix : DEFAULT_PREFIX;
        this.inMemoryMode = false;
        this.mockCache = null;
    }

    public RedisBytecodeStore(String host, int port) {
        this(host, port, null, DEFAULT_PREFIX);
    }

    public RedisBytecodeStore(String host, int port, String password, String keyPrefix) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(16);
        config.setMaxIdle(8);
        config.setMinIdle(2);
        if (password != null && !password.isBlank()) {
            this.jedisPool = new JedisPool(config, host, port, 2000, password);
        } else {
            this.jedisPool = new JedisPool(config, host, port, 2000);
        }
        this.keyPrefix = keyPrefix != null ? keyPrefix : DEFAULT_PREFIX;
        this.inMemoryMode = false;
        this.mockCache = null;
    }

    private RedisBytecodeStore(Map<String, byte[]> mockCache, String keyPrefix) {
        this.jedisPool = null;
        this.keyPrefix = keyPrefix;
        this.inMemoryMode = true;
        this.mockCache = mockCache;
    }

    /**
     * Creates an in-memory simulated Redis cache store for testing or offline environments.
     */
    public static RedisBytecodeStore inMemory() {
        return new RedisBytecodeStore(new ConcurrentHashMap<>(), DEFAULT_PREFIX);
    }

    @Override
    public String getStorageType() {
        return "REDIS";
    }

    @Override
    public void save(String className, byte[] bytecode) {
        if (className == null || bytecode == null) {
            return;
        }

        if (inMemoryMode) {
            mockCache.put(className, bytecode);
            return;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            byte[] classKey = (keyPrefix + "class:" + className).getBytes(StandardCharsets.UTF_8);
            jedis.set(classKey, bytecode);

            // Index class under package set
            String pkg = className.contains(".") ? className.substring(0, className.lastIndexOf('.')) : "";
            byte[] pkgKey = (keyPrefix + "pkg:" + pkg).getBytes(StandardCharsets.UTF_8);
            jedis.sadd(pkgKey, className.getBytes(StandardCharsets.UTF_8));

            log.debug("Saved bytecode for {} to Redis (key={})", className, new String(classKey));
        } catch (Exception e) {
            log.error("Failed saving bytecode to Redis for class {}", className, e);
            throw new RuntimeException("Redis error while saving " + className, e);
        }
    }

    @Override
    public Optional<byte[]> load(String className) {
        if (className == null) {
            return Optional.empty();
        }

        if (inMemoryMode) {
            return Optional.ofNullable(mockCache.get(className));
        }

        try (Jedis jedis = jedisPool.getResource()) {
            byte[] classKey = (keyPrefix + "class:" + className).getBytes(StandardCharsets.UTF_8);
            byte[] bytes = jedis.get(classKey);
            return Optional.ofNullable(bytes);
        } catch (Exception e) {
            log.warn("Failed retrieving bytecode from Redis for {}: {}", className, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean contains(String className) {
        if (className == null) {
            return false;
        }
        if (inMemoryMode) {
            return mockCache.containsKey(className);
        }
        try (Jedis jedis = jedisPool.getResource()) {
            byte[] classKey = (keyPrefix + "class:" + className).getBytes(StandardCharsets.UTF_8);
            return jedis.exists(classKey);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Map<String, byte[]> loadPackage(String packageName, boolean recurse) {
        if (inMemoryMode) {
            Map<String, byte[]> result = new HashMap<>();
            for (Map.Entry<String, byte[]> entry : mockCache.entrySet()) {
                String cn = entry.getKey();
                String pkg = cn.contains(".") ? cn.substring(0, cn.lastIndexOf('.')) : "";
                if (pkg.equals(packageName) || (recurse && (packageName.isEmpty() || pkg.startsWith(packageName + ".")))) {
                    result.put(cn, entry.getValue());
                }
            }
            return result;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, byte[]> result = new HashMap<>();
            byte[] pkgKey = (keyPrefix + "pkg:" + packageName).getBytes(StandardCharsets.UTF_8);
            Set<byte[]> members = jedis.smembers(pkgKey);
            if (members != null) {
                for (byte[] m : members) {
                    String className = new String(m, StandardCharsets.UTF_8);
                    byte[] bytes = jedis.get((keyPrefix + "class:" + className).getBytes(StandardCharsets.UTF_8));
                    if (bytes != null) {
                        result.put(className, bytes);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed listing package {} from Redis: {}", packageName, e.getMessage());
            return Collections.emptyMap();
        }
    }

    @Override
    public void clear() {
        if (inMemoryMode) {
            mockCache.clear();
            return;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> keys = jedis.keys(keyPrefix + "*");
            if (keys != null && !keys.isEmpty()) {
                jedis.del(keys.toArray(new String[0]));
            }
        } catch (Exception e) {
            log.warn("Failed clearing Redis cache: {}", e.getMessage());
        }
    }
}
