# ── Etapa 1: build con Gradle ──────────────────────────────────────────
FROM gradle:8.5-jdk21 AS build

WORKDIR /app

COPY settings.gradle build.gradle ./
COPY ms-gateway ms-gateway
COPY ms-inventario ms-inventario
COPY ms-ventas ms-ventas
COPY ms-consola ms-consola

RUN gradle :ms-consola:shadowJar -x test --no-daemon

# ── Etapa 2: imagen final, solo runtime ────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=build /app/ms-consola/build/libs/*-all.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
