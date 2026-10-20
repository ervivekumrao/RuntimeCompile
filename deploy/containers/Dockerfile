# syntax=docker/dockerfile:1
# ==============================================================================
# Stage 1: Build Application Distribution (Hermetic Build Environment)
# ==============================================================================
FROM docker.io/library/eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace

# Copy Gradle build descriptor files for dependency resolution
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ ./gradle/

# Copy application source code
COPY src/ ./src/

# Use prebuilt distribution if available (sub-second inner-loop build), or build hermetically
COPY build/install/runtime-compile* /workspace/prebuilt/
RUN if [ -d "/workspace/prebuilt/bin" ]; then \
        cp -r /workspace/prebuilt /workspace/dist; \
    elif [ -d "/workspace/prebuilt/runtime-compile/bin" ]; then \
        cp -r /workspace/prebuilt/runtime-compile /workspace/dist; \
    else \
        ./gradlew installDist --no-daemon && cp -r build/install/runtime-compile /workspace/dist; \
    fi

# ==============================================================================
# Stage 2: Production Hardened Runtime Image
# ==============================================================================
FROM docker.io/library/eclipse-temurin:21-jdk-jammy AS runtime

# OpenContainers image metadata labels
LABEL org.opencontainers.image.title="RuntimeCompile" \
      org.opencontainers.image.description="Enterprise In-Memory Java Runtime Compilation, JIT Execution & Tiered Bytecode Microservice" \
      org.opencontainers.image.version="1.0.0" \
      org.opencontainers.image.vendor="Umrao Enterprise" \
      org.opencontainers.image.licenses="Apache-2.0"

# Principle of Least Privilege: Create unprivileged system group and user
RUN groupadd -g 10001 compiler && \
    useradd -u 10001 -g compiler -m -s /bin/bash compiler

# Create application and persistent volume directories with strict ownership
WORKDIR /app
RUN mkdir -p /var/data/compiler/classes /app && \
    chown -R compiler:compiler /var/data/compiler/classes /app && \
    chmod -R 775 /var/data/compiler/classes

# Copy installed distribution from builder stage
COPY --from=builder --chown=compiler:compiler /workspace/dist /app

# Cloud-native container JVM tuning and 12-factor default environment variables
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom" \
    SERVER_PORT=8080 \
    COMPILER_SERVICE=true \
    STORAGE_DIR=/var/data/compiler/classes \
    REDIS_HOST="" \
    REDIS_PORT=6379 \
    REDIS_PASSWORD="" \
    DB_URL="" \
    DB_USER="" \
    DB_PASSWORD=""

# Expose HTTP REST service port
EXPOSE 8080

# Declare persistent volume mount for Tier 3 local filesystem storage
VOLUME ["/var/data/compiler/classes"]

# Switch to unprivileged runtime user (CIS Docker Benchmark compliant)
USER compiler:compiler

# Healthcheck probe for orchestrators (Podman / Kubernetes / Docker)
HEALTHCHECK --interval=20s --timeout=3s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:${SERVER_PORT}/api/health || exit 1

# Exec form entrypoint for clean PID 1 signal handling and graceful shutdown
ENTRYPOINT ["/app/bin/runtime-compile", "--server"]
