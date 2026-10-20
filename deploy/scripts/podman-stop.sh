#!/usr/bin/env bash

POD_NAME="compiler-pod"

echo "Stopping containers in pod: $POD_NAME..."
podman stop runtime-compiler-service compiler-postgres compiler-redis 2>/dev/null || true

echo "Removing containers..."
podman rm runtime-compiler-service compiler-postgres compiler-redis 2>/dev/null || true

echo "Removing pod: $POD_NAME..."
podman pod rm $POD_NAME 2>/dev/null || true

echo "RuntimeCompile pod and containers stopped."
