# ── Etapa 1: build con Gradle ──────────────────────────────────────────
FROM gradle:8.5-jdk21 AS build

WORKDIR /app

# Copiamos todo el proyecto multi-módulo (Gradle necesita ver los 3 módulos
# aunque solo vayamos a compilar uno, por la config compartida en build.gradle raíz)
COPY settings.gradle build.gradle ./
COPY ms-gateway ms-gateway
COPY ms-inventario ms-inventario
COPY ms-ventas ms-ventas
COPY ms-consola ms-consola

# Compila el fat JAR (con todas las dependencias incluidas: Gson, HikariCP, MySQL connector)
RUN gradle :ms-gateway:shadowJar -x test --no-daemon

# ── Etapa 2: imagen final, solo runtime ────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copiamos únicamente el fat JAR (termina en -all.jar) desde la etapa de build
COPY --from=build /app/ms-gateway/build/libs/*-all.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
