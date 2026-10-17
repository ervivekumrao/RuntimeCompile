package umrao.compile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import umrao.compile.api.CompilationResult;
import umrao.compile.api.DynamicCompiler;
import umrao.compile.engine.InMemoryCompiler;
import umrao.std.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyAndStressTest {

    @Test
    @DisplayName("Should successfully handle concurrent compilations across multiple threads without race conditions")
    @SuppressWarnings("resource")
    void testConcurrentCompilationStress() throws Exception {
        DynamicCompiler compiler = new InMemoryCompiler();
        int threadCount = 8;
        int tasksPerThread = 5;
        int totalTasks = threadCount * tasksPerThread;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            CountDownLatch readyLatch = new CountDownLatch(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);

            List<Future<CompilationResult<Message>>> futures = new ArrayList<>();

            for (int i = 0; i < totalTasks; i++) {
                final int id = i;
                futures.add(executor.submit(() -> {
                    readyLatch.countDown();
                    startLatch.await(); // Synchronize all threads to start compiling at the exact same moment

                    String className = "umrao.generated.StressClass" + id;
                    String code = String.format("""
                            package umrao.generated;
                            import umrao.std.Message;
                            
                            public class StressClass%d implements Message {
                                private final int id = %d;
                                @Override
                                public void showMessage() {
                                    System.out.println("Stress class " + id);
                                }
                            }
                            """, id, id);

                    return compiler.compile(className, code, Message.class);
                }));
            }

            // Wait until all worker threads are created and ready
            boolean ready = readyLatch.await(5, TimeUnit.SECONDS);
            assertThat(ready).isTrue();

            // Release the barrier
            startLatch.countDown();

            for (Future<CompilationResult<Message>> future : futures) {
                CompilationResult<Message> result = future.get(30, TimeUnit.SECONDS);
                assertThat(result).isNotNull();
                assertThat(result.isSuccess()).isTrue();
                Message instance = result.newInstance();
                assertThat(instance).isNotNull();
            }
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
