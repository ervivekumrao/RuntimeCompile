package umrao.compile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import umrao.compile.api.CompilationResult;
import umrao.compile.api.DynamicCompiler;
import umrao.compile.engine.InMemoryCompiler;
import umrao.compile.store.BytecodeStore;
import umrao.compile.store.DatabaseBytecodeStore;
import umrao.compile.store.FileSystemBytecodeStore;
import umrao.compile.store.RedisBytecodeStore;
import umrao.compile.store.TieredBytecodeRegistry;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TieredBytecodeStoreTest {

    @Test
    @DisplayName("FileSystemBytecodeStore: Should save, load, and discover classes in compiler pod filesystem")
    void testFileSystemStore(@TempDir Path tempDir) {
        BytecodeStore fsStore = new FileSystemBytecodeStore(tempDir);
        byte[] dummyBytecode = new byte[]{1, 2, 3, 4};

        fsStore.save("com.example.Demo", dummyBytecode);

        assertThat(fsStore.contains("com.example.Demo")).isTrue();
        Optional<byte[]> loaded = fsStore.load("com.example.Demo");
        assertThat(loaded).isPresent();
        assertThat(loaded.get()).isEqualTo(dummyBytecode);

        assertThat(fsStore.loadPackage("com.example", false)).containsKey("com.example.Demo");
    }

    @Test
    @DisplayName("RedisBytecodeStore: Should save, load, and discover classes in in-memory distributed cache")
    void testRedisStore() {
        BytecodeStore redisStore = RedisBytecodeStore.inMemory();
        byte[] dummyBytecode = new byte[]{10, 20, 30};

        redisStore.save("com.example.CachedClass", dummyBytecode);

        assertThat(redisStore.contains("com.example.CachedClass")).isTrue();
        Optional<byte[]> loaded = redisStore.load("com.example.CachedClass");
        assertThat(loaded).isPresent();
        assertThat(loaded.get()).isEqualTo(dummyBytecode);

        assertThat(redisStore.loadPackage("com.example", false)).containsKey("com.example.CachedClass");
    }

    @Test
    @DisplayName("DatabaseBytecodeStore: Should persist, load, and query classes using external SQL database")
    void testDatabaseStore() {
        BytecodeStore dbStore = DatabaseBytecodeStore.h2InMemory("test_db_store");
        byte[] dummyBytecode = new byte[]{42, 43, 44};

        dbStore.save("com.example.PersistentEntity", dummyBytecode);

        assertThat(dbStore.contains("com.example.PersistentEntity")).isTrue();
        Optional<byte[]> loaded = dbStore.load("com.example.PersistentEntity");
        assertThat(loaded).isPresent();
        assertThat(loaded.get()).isEqualTo(dummyBytecode);

        assertThat(dbStore.loadPackage("com.example", false)).containsKey("com.example.PersistentEntity");
    }

    @Test
    @DisplayName("TieredBytecodeRegistry: Should enforce strict lookup order ClassLoader > Redis > Filesystem > DB and warm upper tiers")
    void testTieredLookupPriorityAndWarming(@TempDir Path tempDir) {
        BytecodeStore redisStore = RedisBytecodeStore.inMemory();
        BytecodeStore fsStore = new FileSystemBytecodeStore(tempDir);
        BytecodeStore dbStore = DatabaseBytecodeStore.h2InMemory("tiered_warming_db");

        byte[] dbBytes = new byte[]{1, 1, 1};
        byte[] fsBytes = new byte[]{2, 2, 2};
        byte[] redisBytes = new byte[]{3, 3, 3};

        // 1. Put Item A only in DB (Tier 4)
        dbStore.save("com.test.ItemA", dbBytes);

        TieredBytecodeRegistry registry = TieredBytecodeRegistry.builder()
                .withRedisStore(redisStore)
                .withFileSystemStore(fsStore)
                .withDatabaseStore(dbStore)
                .build();

        // Lookup Item A: Should resolve from DB and automatically warm Filesystem and Redis!
        Optional<byte[]> foundA = registry.findBytecode("com.test.ItemA");
        assertThat(foundA).isPresent();
        assertThat(foundA.get()).isEqualTo(dbBytes);

        // Verify warming occurred in Redis and Filesystem tiers
        assertThat(fsStore.contains("com.test.ItemA")).isTrue();
        assertThat(redisStore.contains("com.test.ItemA")).isTrue();

        // 2. Put Item B in Filesystem (Tier 3) and DB (Tier 4) -> Filesystem must win
        fsStore.save("com.test.ItemB", fsBytes);
        dbStore.save("com.test.ItemB", dbBytes);

        Optional<byte[]> foundB = registry.findBytecode("com.test.ItemB");
        assertThat(foundB).isPresent();
        assertThat(foundB.get()).isEqualTo(fsBytes); // Filesystem prioritized over DB
        assertThat(redisStore.contains("com.test.ItemB")).isTrue(); // Warmed into Redis

        // 3. Put Item C in Redis (Tier 2), Filesystem (Tier 3), and DB (Tier 4) -> Redis must win
        redisStore.save("com.test.ItemC", redisBytes);
        fsStore.save("com.test.ItemC", fsBytes);
        dbStore.save("com.test.ItemC", dbBytes);

        Optional<byte[]> foundC = registry.findBytecode("com.test.ItemC");
        assertThat(foundC).isPresent();
        assertThat(foundC.get()).isEqualTo(redisBytes); // Redis prioritized over Filesystem & DB
    }

    @Test
    @DisplayName("End-to-End Compiler: Should resolve dependent class from external DB across independent compiler instances")
    void testCompilerResolvesDependencyFromDatabase() throws Exception {
        BytecodeStore dbStore = DatabaseBytecodeStore.h2InMemory("e2e_db");

        // Compiler 1: Compiles 'Product' model and persists it to Database
        DynamicCompiler compiler1 = InMemoryCompiler.builder()
                .withDatabaseStore(dbStore)
                .build();

        String productCode = """
                package umrao.inventory;

                public class Product {
                    private final String sku;
                    private final double basePrice;

                    public Product(String sku, double basePrice) {
                        this.sku = sku;
                        this.basePrice = basePrice;
                    }
                    public String getSku() { return sku; }
                    public double getBasePrice() { return basePrice; }
                }
                """;

        compiler1.compile(productCode);
        assertThat(dbStore.contains("umrao.inventory.Product")).isTrue();

        // Compiler 2: Fresh compiler instance sharing ONLY the Database
        DynamicCompiler compiler2 = InMemoryCompiler.builder()
                .withDatabaseStore(dbStore)
                .build();

        String pricingServiceCode = """
                package umrao.inventory.service;

                import umrao.inventory.Product;

                public class PricingService {
                    public double calculateDiscountedPrice(Product product, double discountPercent) {
                        return product.getBasePrice() * (1.0 - discountPercent);
                    }
                }
                """;

        // Should resolve 'Product' from DB during compile time and runtime!
        CompilationResult<Object> serviceResult = compiler2.compile(pricingServiceCode);
        assertThat(serviceResult.isSuccess()).isTrue();

        // Execute at runtime using classes loaded from database
        assertThat(compiler2.getTieredRegistry().findBytecode("umrao.inventory.Product")).isPresent();
        Class<?> prodClass = compiler2.loadClass("umrao.inventory.Product");
        Object productInstance = prodClass.getConstructor(String.class, double.class).newInstance("SKU-999", 200.0);

        Object serviceInstance = serviceResult.newInstance();
        Object finalPrice = serviceResult.invokeOn(serviceInstance, "calculateDiscountedPrice", productInstance, 0.25);

        assertThat(finalPrice).isEqualTo(150.0);
    }

    @Test
    @DisplayName("End-to-End Compiler: Should resolve dependent class from Redis cache across independent compiler instances")
    void testCompilerResolvesDependencyFromRedis() {
        BytecodeStore redisStore = RedisBytecodeStore.inMemory();

        // Compiler 1 saves compiled class to Redis
        DynamicCompiler compiler1 = InMemoryCompiler.builder()
                .withRedisStore(redisStore)
                .build();

        String accountCode = """
                package umrao.finance;
                public class Account {
                    private final double balance;
                    public Account(double balance) { this.balance = balance; }
                    public double getBalance() { return balance; }
                }
                """;
        compiler1.compile(accountCode);
        assertThat(redisStore.contains("umrao.finance.Account")).isTrue();

        // Compiler 2 starts up fresh with only Redis configured
        DynamicCompiler compiler2 = InMemoryCompiler.builder()
                .withRedisStore(redisStore)
                .build();

        String auditCode = """
                package umrao.finance.audit;
                import umrao.finance.Account;

                public class AccountAuditor {
                    public boolean isHealthy(Account account) {
                        return account.getBalance() > 0;
                    }
                }
                """;

        CompilationResult<Object> auditResult = compiler2.compile(auditCode);
        assertThat(auditResult.isSuccess()).isTrue();
    }
}
