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
    implementation(libs.bytebuddy.core)
    implementation(libs.bytebuddy.agent)
    implementation(libs.janino)
    implementation(libs.janino.compiler)
    implementation(libs.javassist)
    implementation(libs.groovy.core)
    implementation(libs.groovy.jsr223)
    implementation(libs.mvel)

    implementation(libs.jedis)
    implementation(libs.h2)
    implementation(libs.postgresql)

    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
    testImplementation(libs.awaitility)
    testRuntimeOnly(libs.junit.platform.launcher)
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
