package umrao.compile.jit;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.LoadedTypeInitializer;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatchers;
import umrao.compile.engine.MemoryClassLoader;

import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Byte Buddy Dynamic Runtime Synthesizer:
 * Generates runtime subclasses, interface implementations, and method interceptors directly in bytecode
 * without needing source code or javac compilation.
 */
public class ByteBuddyRuntimeGenerator {

    private static final AtomicLong COUNTER = new AtomicLong();
    private final ByteBuddy byteBuddy;

    public ByteBuddyRuntimeGenerator() {
        this.byteBuddy = new ByteBuddy();
    }

    /**
     * Synthesizes an implementation of a target interface with custom interceptor logic.
     */
    @SuppressWarnings("unchecked")
    public <T> T createProxy(Class<T> targetInterface, Object interceptor) {
        try {
            String generatedName = interceptor.getClass().getPackageName() + ".ByteBuddyProxy$" + COUNTER.incrementAndGet();

            Class<?> dynamicType;
            try (DynamicType.Unloaded<T> unloaded = byteBuddy
                    .subclass(targetInterface)
                    .name(generatedName)
                    .modifiers(Modifier.PUBLIC)
                    .method(ElementMatchers.isDeclaredBy(targetInterface))
                    .intercept(MethodDelegation.to(interceptor))
                    .make()) {

                byte[] bytecode = unloaded.getBytes();

                MemoryClassLoader loader = new MemoryClassLoader(
                        interceptor.getClass().getClassLoader(),
                        Map.of(generatedName, bytecode)
                );

                dynamicType = loader.loadClass(generatedName);

                // Execute Byte Buddy's type initializers to wire delegate fields
                for (Map.Entry<TypeDescription, LoadedTypeInitializer> entry : unloaded.getLoadedTypeInitializers().entrySet()) {
                    entry.getValue().onLoad(dynamicType);
                }
            }

            return (T) dynamicType.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Byte Buddy synthesis failed for interface " + targetInterface.getName(), e);
        }
    }
}
