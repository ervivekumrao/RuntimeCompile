#!/usr/bin/env bash
set -e

POD_NAME="compiler-pod"
VOLUME_NAME="compiler-classes-vol"
PG_VOLUME="compiler-pg-vol"
REDIS_VOLUME="compiler-redis-vol"

echo "=== [1/4] Ensuring Podman volumes exist and configuring permissions ==="
podman volume create $VOLUME_NAME 2>/dev/null || true
podman volume create $PG_VOLUME 2>/dev/null || true
podman volume create $REDIS_VOLUME 2>/dev/null || true

# Ensure non-root compiler user (UID 10001) owns the classes storage volume
podman run --rm -v $VOLUME_NAME:/data docker.io/library/eclipse-temurin:21-jdk-jammy chown -R 10001:10001 /data 2>/dev/null || true

echo "=== [2/4] Ensuring Podman pod exists: $POD_NAME ==="
if ! podman pod exists $POD_NAME 2>/dev/null; then
    podman pod create --name $POD_NAME \
      -p 8080:8080 \
      -p 6379:6379 \
      -p 5432:5432
    echo "Pod $POD_NAME created."
else
    echo "Pod $POD_NAME already exists."
fi

echo "=== [3/4] Starting PostgreSQL & Redis storage tier containers ==="
if ! podman container exists compiler-postgres 2>/dev/null; then
    podman run -d --pod $POD_NAME \
      --name compiler-postgres \
      --restart unless-stopped \
      -e POSTGRES_DB=bytecode_db \
      -e POSTGRES_USER=compiler_user \
      -e POSTGRES_PASSWORD=compiler_pass \
      -v $PG_VOLUME:/var/lib/postgresql/data \
      docker.io/library/postgres:15
    echo "compiler-postgres container started."
else
    podman start compiler-postgres 2>/dev/null || true
fi

if ! podman container exists compiler-redis 2>/dev/null; then
    podman run -d --pod $POD_NAME \
      --name compiler-redis \
      --restart unless-stopped \
      -v $REDIS_VOLUME:/data \
      docker.io/library/redis:7.2-alpine
    echo "compiler-redis container started."
else
    podman start compiler-redis 2>/dev/null || true
fi

echo "=== Waiting for storage backends to initialize ==="
sleep 3

echo "=== [4/4] Starting RuntimeCompile REST microservice ==="
if ! podman container exists runtime-compiler-service 2>/dev/null; then
    podman run -d --pod $POD_NAME \
      --name runtime-compiler-service \
      --restart unless-stopped \
      -e SERVER_PORT=8080 \
      -e COMPILER_SERVICE=true \
      -e STORAGE_DIR=/var/data/compiler/classes \
      -e REDIS_HOST=localhost \
      -e REDIS_PORT=6379 \
      -e DB_URL=jdbc:postgresql://localhost:5432/bytecode_db \
      -e DB_USER=compiler_user \
      -e DB_PASSWORD=compiler_pass \
      -v $VOLUME_NAME:/var/data/compiler/classes \
      localhost/runtime-compile:latest
    echo "runtime-compiler-service container started."
else
    podman start runtime-compiler-service 2>/dev/null || true
fi

echo ""
echo "================================================================================"
echo "RuntimeCompile service successfully launched in Podman pod: $POD_NAME"
echo "  - Service URL: http://localhost:8080"
echo "  - Health Check: curl http://localhost:8080/api/health"
echo "  - Tiers Status: curl http://localhost:8080/api/tiers"
echo "================================================================================"
