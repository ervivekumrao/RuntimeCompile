package umrao.compile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import umrao.compile.api.CompilationResult;
import umrao.compile.api.DynamicCompiler;
import umrao.compile.engine.InMemoryCompiler;
import umrao.std.Message;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class AdvancedCompilationTest {

    private final DynamicCompiler compiler = new InMemoryCompiler();

    @Test
    @DisplayName("Should compile and instantiate a Java Record implementing Message")
    void testRecordCompilation() {
        String code = """
                package umrao.generated;
                import umrao.std.Message;
                
                public record RecordMessage(String text) implements Message {
                    public RecordMessage() {
                        this("Default Record Message");
                    }
                    @Override
                    public void showMessage() {
                        System.out.println(text);
                    }
                }
                """;

        CompilationResult<Message> result = compiler.compile("umrao.generated.RecordMessage", code, Message.class);
        assertThat(result.isSuccess()).isTrue();

        Message msg = result.newInstance();
        assertThat(msg).isNotNull();
        assertThat(result.getCompiledClass().isRecord()).isTrue();
    }

    @Test
    @DisplayName("Should compile dynamic class implementing generic java.util.function.Function")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void testGenericFunctionCompilation() {
        String code = """
                package umrao.generated;
                import java.util.function.Function;
                
                public class StringReverser implements Function<String, String> {
                    @Override
                    public String apply(String input) {
                        return new StringBuilder(input).reverse().toString();
                    }
                }
                """;

        Class<Function> functionType = Function.class;
        CompilationResult<Function> result = compiler.compile("umrao.generated.StringReverser", code, functionType);
        Function<String, String> reverser = (Function<String, String>) result.newInstance();

        assertThat(reverser.apply("Hello Modern Java")).isEqualTo("avaJ nredoM olleH");
    }

    @Test
    @DisplayName("Should compile class containing static nested classes and lambda expressions")
    void testInnerClassesAndLambdas() throws Exception {
        String code = """
                package umrao.generated;
                import java.util.List;
                import java.util.stream.Collectors;
                
                public class LambdaProcessor {
                    public static class Helper {
                        public static int square(int n) { return n * n; }
                    }

                    public List<Integer> processSquares(List<Integer> numbers) {
                        return numbers.stream()
                                      .map(Helper::square)
                                      .collect(Collectors.toList());
                    }
                }
                """;

        Class<?> clazz = compiler.compile("umrao.generated.LambdaProcessor", code).getCompiledClass();
        Object instance = clazz.getDeclaredConstructor().newInstance();

        @SuppressWarnings("unchecked")
        List<Integer> result = (List<Integer>) clazz
                .getMethod("processSquares", List.class)
                .invoke(instance, List.of(1, 2, 3, 4, 5));

        assertThat(result).containsExactly(1, 4, 9, 16, 25);
    }
}
