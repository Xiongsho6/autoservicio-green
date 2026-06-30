# ── Etapa 1: build con Gradle ──────────────────────────────────────────
FROM gradle:8.5-jdk21 AS build

WORKDIR /app

# Copiamos todo el proyecto multi-módulo (Gradle necesita ver los 3 módulos
# aunque solo vayamos a compilar uno, por la config compartida en build.gradle raíz)
COPY settings.gradle build.gradle ./
COPY ms-gateway ms-gateway
COPY ms-inventario ms-inventario
COPY ms-ventas ms-ventas

# Compila solo el módulo ms-gateway, sin correr tests, generando el JAR
RUN gradle :ms-gateway:build -x test --no-daemon

# ── Etapa 2: imagen final, solo runtime ────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copiamos únicamente el JAR ya compilado desde la etapa de build
COPY --from=build /app/ms-gateway/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
