package umrao.compile.jit;

/**
 * Enumeration of modern runtime compilation and JIT frameworks supported by RuntimeCompile.
 */
public enum JitEngineType {
    /**
     * Standard Java Compiler API (javax.tools.JavaCompiler) with in-memory Bytecode buffers.
     */
    JAVAC_IN_MEMORY("Java Compiler API (javax.tools)", "Full Java language specification, standard JDK"),

    /**
     * Byte Buddy: Modern declarative runtime class generation and method interception.
     */
    BYTE_BUDDY("Byte Buddy", "Direct bytecode generation without source compilation"),

    /**
     * Janino: Ultra-fast embedded lightweight Java compiler (used by Apache Spark / Calcite).
     */
    JANINO("Janino JIT Compiler", "Ultra-fast embedded compiler for expressions, scripts, and classes"),

    /**
     * Javassist: Source-level bytecode manipulation and dynamic class pool.
     */
    JAVASSIST("Javassist", "Bytecode engineering and runtime class mutation"),

    /**
     * Apache Groovy with @CompileStatic: JIT bytecode generation for dynamic scripts.
     */
    GROOVY_COMPILE_STATIC("Apache Groovy (Static JIT)", "Dynamic scripting compiled directly to static JVM bytecode"),

    /**
     * MVEL 2: High-throughput compiled expression and evaluation engine.
     */
    MVEL_JIT("MVEL Compiled JIT", "Pre-compiled expression bytecode for sub-microsecond evaluation");

    private final String displayName;
    private final String description;

    JitEngineType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
