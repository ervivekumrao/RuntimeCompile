# RuntimeCompile

A high-performance, in-memory Java runtime compilation, JIT execution, and bytecode synthesis engine. It enables compiling and executing dynamic Java code, scripts, and expressions entirely in RAM with zero disk I/O, sub-millisecond caching, and sandboxed security.

---

## ⚡ Quick Start

### Prerequisites
- **Java 17 or 21+** (The included Gradle Wrapper automatically provisions a JDK if missing).

### Build and Run
```bash
# 1. Run the application and multi-engine benchmark
./gradlew run --quiet

# 2. Run all tests (47 tests)
./gradlew test

# 3. Clean build artifacts
./gradlew clean
```

---

## 🚀 Supported Engines

| Engine                            | Paradigm                      | Best For                                                 |
| :-------------------------------- | :---------------------------- | :------------------------------------------------------- |
| **Java Compiler (`javax.tools`)** | In-Memory Java Compilation    | Full Java classes and complex source code in RAM.        |
| **Janino JIT**                    | Embedded Lightweight Compiler | High-speed query/script JIT compilation (< 20ms).        |
| **Byte Buddy**                    | Declarative Bytecode Synthesis| Dynamic proxies, subclassing, and method interception.   |
| **Javassist**                     | Bytecode Transformation       | Dynamic method injection and structural class modification. |
| **Apache Groovy**                 | Static JIT Scripting          | Dynamic business rules compiled with `@CompileStatic`.   |
| **MVEL 2**                        | Pre-Compiled Expressions      | High-throughput math/logic expressions (< 10µs).         |

---

## 💻 Usage Examples

### 1. In-Memory Java Compilation (`javax.tools`)
Compiles standard Java source code directly in RAM and loads it into an isolated ClassLoader:

```java
public class InMemoryDemo {
    public static void main(String[] args) {
        DynamicCompiler compiler = new InMemoryCompiler();

        String code = """
            package umrao.generated;
            import umrao.std.Message;
            
            public class DynamicGreeter implements Message {
                @Override
                public void showMessage() {
                    System.out.println("Hello from dynamic in-memory code!");
                }
            }
            """;

        // Compile, cache, and instantiate
        CompilationResult<Message> result = compiler.compile("umrao.generated.DynamicGreeter", code, Message.class);
        Message greeter = result.newInstance();
        greeter.showMessage();
    }
}
```

### 2. Ultra-Fast Class & Expression JIT (Janino)
```java
void runJanino(String sourceCode) {
    JaninoJitCompiler janino = new JaninoJitCompiler();

    // Compile class in < 20ms
    Message msg = janino.compileAndInstantiate("umrao.generated.JaninoMsg", sourceCode, Message.class);
    msg.showMessage();

    // Evaluate dynamic expression
    Object result = janino.evaluateExpression(
        "x * 2 + y",
        Integer.class,
        new String[]{"x", "y"},
        new Class<?>[]{int.class, int.class},
        new Object[]{10, 5}
    ); // returns 25
}
```

### 3. Dynamic Proxies & Subclassing (Byte Buddy)
Synthesizes classes at runtime without source code or `javac`:

```java
void runByteBuddy() {
    ByteBuddyRuntimeGenerator byteBuddy = new ByteBuddyRuntimeGenerator();

    Message proxy = byteBuddy.createProxy(Message.class, new Object() {
        public void showMessage() {
            System.out.println("Intercepted by Byte Buddy proxy!");
        }
    });
    proxy.showMessage();
}
```

### 4. Groovy JIT Scripting
```java
void runGroovy() {
    GroovyJitCompiler groovy = new GroovyJitCompiler();

    String script = """
        package umrao.generated
        import umrao.std.Message
        import groovy.transform.CompileStatic

        @CompileStatic
        class GroovyMsg implements Message {
            void showMessage() { println "Executed via Groovy @CompileStatic JIT!" }
        }
    """;

    Message msg = groovy.compileAndInstantiate(script, Message.class);
    msg.showMessage();
}
```

### 5. High-Throughput Expressions (MVEL)
```java
void runMvel() {
    MvelJitEngine mvel = new MvelJitEngine();

    // Pre-compile once
    Serializable compiled = mvel.compile("price * quantity * (1 - discount)");

    // Execute repeatedly in sub-microseconds
    Map<String, Object> ctx = Map.of("price", 100.0, "quantity", 5, "discount", 0.10);
    Object total = mvel.execute(compiled, ctx); // returns 450.0
}
```

---

## 🔒 Security & Sandboxing

To prevent arbitrary code execution (RCE) and infinite loops, `InMemoryCompiler` includes configurable policies:

```java
void configureSecurity(Message greeter) {
    // 1. Strict Enterprise Policy (Default: blocks Runtime.getRuntime, ProcessBuilder, System.exit, reflection)
    DynamicCompiler secureCompiler = new InMemoryCompiler(SecurityPolicy.strict(), null, null);

    // 2. Custom Policy
    SecurityPolicy policy = SecurityPolicy.builder()
            .blockImport("java.net")
            .blockPattern("System.exit")
            .maxSourceLength(100_000)
            .enforceSandbox(true)
            .build();

    // 3. Execution Timeout Protection (protects against infinite loops / DoS)
    SandboxedExecutor executor = new SandboxedExecutor(Duration.ofSeconds(3));
    executor.execute(greeter::showMessage);
}
```

---

## 🪵 Logging

The project uses the **SLF4J 2.x** facade with **Logback Classic**.
- Configure log levels in [`src/main/resources/logback.xml`](src/main/resources/logback.xml).
- To switch logging backends (e.g. to Log4j2 or OpenTelemetry), simply change the runtime dependency in `build.gradle.kts`—no application code changes required.

---

## 🏗️ Project Structure

```
RuntimeCompile/
├── build.gradle.kts           # Gradle configuration & dependencies
├── settings.gradle.kts        # Foojay JDK toolchain auto-resolver
├── gradle.properties          # JVM & daemon performance tuning
├── gradlew / gradlew.bat      # Cross-platform Gradle Wrapper
└── src/
    ├── main/
    │   ├── java/
    │   │   ├── Main.java      # Demo entry point & multi-engine benchmark
    │   │   └── umrao/
    │   │       ├── compile/
    │   │       │   ├── api/       # DynamicCompiler & CompilationResult
    │   │       │   ├── cache/     # Thread-safe SHA-256 LRU cache
    │   │       │   ├── engine/    # javax.tools in-memory compiler & MemoryClassLoader
    │   │       │   ├── jit/       # Janino, ByteBuddy, Javassist, Groovy, MVEL
    │   │       │   └── security/  # SecurityPolicy & SandboxedExecutor
    │   │       └── std/           # Message interface & Log utility
    │   └── resources/
    │       ├── JavaCode.txt   # Demo source template
    │       └── logback.xml    # Logging configuration
    └── test/java/             # 47 JUnit 5 unit, security, and stress tests
```

---

## 📄 License

Licensed under the [Apache License 2.0](LICENSE).
