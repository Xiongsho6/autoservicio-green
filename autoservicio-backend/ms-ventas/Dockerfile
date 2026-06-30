# ── Etapa 1: build con Gradle ──────────────────────────────────────────
FROM gradle:8.5-jdk21 AS build

WORKDIR /app

COPY settings.gradle build.gradle ./
COPY ms-gateway ms-gateway
COPY ms-inventario ms-inventario
COPY ms-ventas ms-ventas

RUN gradle :ms-ventas:build -x test --no-daemon

# ── Etapa 2: imagen final, solo runtime ────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=build /app/ms-ventas/build/libs/*.jar app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
