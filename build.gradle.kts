plugins {
    java
    application
}

group = "com.umrao"
version = "1.0.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("Main")
}

dependencies {
    // --- Modern Bytecode Generation & Runtime Synthesis Frameworks ---
    // Byte Buddy: Industry-standard runtime class creation, subclassing & interception
    implementation("net.bytebuddy:byte-buddy:1.14.18")
    implementation("net.bytebuddy:byte-buddy-agent:1.14.18")

    // Janino: Ultra-fast embedded in-memory Java expression and class compiler
    implementation("org.codehaus.janino:janino:3.1.12")
    implementation("org.codehaus.janino:commons-compiler:3.1.12")

    // Javassist: Bytecode manipulation and dynamic class loader
    implementation("org.javassist:javassist:3.30.2-GA")

    // Apache Groovy: Dynamic scripting with JIT @CompileStatic runtime compiler
    implementation("org.apache.groovy:groovy:4.0.22")
    implementation("org.apache.groovy:groovy-jsr223:4.0.22")

    // MVEL: High-performance expression language with compiled JIT runtime mode
    implementation("org.mvel:mvel2:2.5.2.Final")

    // --- Enterprise Universal Logging Framework ---
    // SLF4J 2.x universal facade: provides total decoupling from concrete logging implementations
    implementation("org.slf4j:slf4j-api:2.0.13")
    // Logback Classic: industry-standard, high-performance backend with async, JSON, and rolling file support
    runtimeOnly("ch.qos.logback:logback-classic:1.5.6")

    // --- In-Depth Testing Framework Suite ---
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.12.0")
    testImplementation("org.awaitility:awaitility:4.2.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
