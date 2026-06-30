# 🌿 Minimarket Autoservicio - Green

Sistema distribuido multiservicio de arquitectura modular que integra un backend en Java (Gradle) con comunicación híbrida (Sockets TCP y HTTP REST), una interfaz de caja registradora nativa en C++ y un frontend web asíncrono para el escaneo de productos en ráfaga (Abarrotes, Bebidas/Licores y Minilibrería).

El backend está compuesto por **3 microservicios** (`ms-gateway`, `ms-inventario`, `ms-ventas`) más un **panel de administración** (`ms-consola`) que permite supervisar el sistema sin duplicar lógica de negocio.

---

## 🛠️ Requisitos Previos (máquina virtual desde cero)

Instala lo siguiente según tu sistema operativo:

- **Docker y Docker Compose** — única dependencia obligatoria para levantar el backend completo (incluye MySQL, así que **no necesitas instalar MySQL/MariaDB por separado**).
  - Windows/macOS: instala [Docker Desktop](https://www.docker.com/products/docker-desktop/).
  - Linux (Debian/Ubuntu):
    ```bash
    sudo apt update
    sudo apt install -y docker.io docker-compose-plugin
    sudo usermod -aG docker $USER   # luego reinicia sesión
    ```
  - Verifica la instalación:
    ```bash
    docker --version
    docker compose version
    ```
- **Git** (para clonar el repositorio):
  ```bash
  sudo apt install -y git      # Linux
  ```
- **Caja (C++):** un compilador de C++ — g++/MinGW en Windows, `build-essential` en Linux, o Clang en macOS.
  ```bash
  sudo apt install -y build-essential   # Linux
  ```
- **Navegador web moderno** (Chrome, Edge, Firefox, Safari) para el frontend.

> Java JDK y Gradle **no son necesarios** si usas Docker — las imágenes los traen incluidos. Solo instálalos si vas a correr el backend manualmente (ver sección al final).

---

## 🚀 Guía de Despliegue con Docker (recomendada)

### Paso 1: Clonar el repositorio

```bash
git clone https://github.com/Xiongsho6/autoservicio-green.git
cd autoservicio-green/autoservicio-backend
```

### Paso 2: Levantar todo el backend con un solo comando

Docker Compose construye las 4 imágenes (gateway, inventario, ventas, consola) y levanta también el contenedor de MySQL, ya con la base de datos `autoservicio_green` creada e inicializada automáticamente con los 30 productos de prueba (no necesitas correr ningún script `.sql` a mano, el `docker-compose.yml` ya monta `autoservicio_green.sql` como script de inicialización).

```bash
docker compose up -d --build
```

Espera unos segundos a que termine. Para confirmar que los 4 contenedores están corriendo:

```bash
docker compose ps
```

Deberías ver `autoservicio-mysql`, `ms-gateway`, `ms-inventario` y `ms-ventas` en estado `running`/`healthy`.

### Paso 3: Ver los logs de cada microservicio (opcional)

Cada servicio loguea por separado. Para ver uno en vivo (deja la terminal abierta):

```bash
docker compose logs -f ms-gateway
docker compose logs -f ms-inventario
docker compose logs -f ms-ventas
```

(`Ctrl + C` cierra solo la vista de logs, no detiene el contenedor).

### Paso 4: Usar el Panel de Supervisión Unificado

`ms-consola` es un cliente de consola interactivo que reemplaza a los 3 menús de supervisor independientes (Gateway/Inventario/Ventas) en un solo panel. No corre en segundo plano: se arranca y se conecta cuando lo necesitas.

```bash
docker compose start ms-consola
docker attach ms-consola
```

Verás:

```
==================================================
   PANEL DE SUPERVISION UNIFICADO - AUTOSERVICIO
==================================================
 [1] Ver logs del Gateway (orquestador)
 [2] Menu de Inventario
 [3] Menu de Ventas
 [4] Salir
```

- **Opción 2** te da el mismo menú de Inventario de siempre (ver stock, agregar producto, reabastecer, etc.), pero ejecutado de forma remota contra `ms-inventario`.
- **Opción 3** lo mismo para Ventas (ver recaudación, reiniciar turno, ver boletas).
- **Opción 1** no tiene menú propio (el Gateway no tiene estado de negocio que consultar); te indica el comando para ver sus logs en otra terminal.

⚠️ **Importante al salir:** para desconectarte de `docker attach` **sin** detener el contenedor, usa la combinación `Ctrl+P` seguido de `Ctrl+Q`. Si usas `Ctrl+C` o eliges `[4] Salir` dentro del menú, el proceso termina y el contenedor se detiene (es normal, ya que es un cliente interactivo, no un servidor). Para volver a usarlo:

```bash
docker compose start ms-consola
docker attach ms-consola
```

### Paso 5: Detener todo

```bash
docker compose down
```

Esto detiene y elimina los contenedores, pero conserva los datos de MySQL (guardados en un volumen Docker). Para reconstruir desde cero si cambiaste código fuente:

```bash
docker compose down
docker compose build --no-cache
docker compose up -d
```

---

## 🧯 Solución de Problemas Comunes

| Síntoma                                                                          | Causa                                                                                                       | Solución                                                                                                                                 |
| -------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| `no main manifest attribute, in app.jar`                                         | Docker compiló con `gradle build` en vez de `shadowJar` (jar sin manifest ejecutable)                       | Verifica que los `Dockerfile` usen `gradle :servicio:shadowJar` y copien `*-all.jar`. Reconstruye con `docker compose build --no-cache`. |
| `Exception in thread "Thread-1" java.util.NoSuchElementException: No line found` | El menú de supervisor intentaba leer consola sin que el contenedor tuviera una terminal interactiva abierta | Ya corregido: el menú detecta ausencia de consola y se desactiva limpiamente. Usa `ms-consola` para supervisar en su lugar.              |
| `volumes must be a mapping` al validar `docker-compose.yml`                      | Indentación rota en el YAML (común al copiar/pegar)                                                         | Descarga/copia el archivo completo en vez de editarlo a mano línea por línea.                                                            |
| `docker attach ms-consola` → `cannot attach to a stopped container`              | El contenedor de `ms-consola` ya terminó (eliges "Salir" o cerraste con Ctrl+C)                             | Vuelve a iniciarlo: `docker compose start ms-consola` antes de hacer `attach`.                                                           |

---

## 🌐 Puertos del Sistema

| Servicio      | Puerto | Protocolo  |
| ------------- | ------ | ---------- |
| MySQL         | 3306   | TCP        |
| ms-inventario | 8081   | TCP (JSON) |
| ms-ventas     | 8082   | TCP (JSON) |
| ms-gateway    | 8080   | HTTP REST  |

---

### Paso 6: Lanzar la Interfaz de Escaneo Web (Frontend)

El frontend se comunica con el Gateway de manera asíncrona.

1. Navega a la carpeta correspondiente o ubica el archivo de la interfaz.
2. Abre el archivo `index.html` directamente en cualquier navegador web moderno (Chrome, Edge, Firefox, Safari).
3. Utiliza la barra de entrada para simular las ráfagas de escaneo de productos mediante los códigos de barra del catálogo oficial.

---

### Paso 7: Compilar y Ejecutar la Caja Registradora (C++)

Navega a la carpeta de la caja física:

```bash
cd autoservicio-caja
```

Compila y ejecuta el binario nativo según tu sistema operativo:

- **Windows (MinGW / GCC):**
  ```bash
  g++ -o Cliente.exe Cliente.cpp -lws2_32
  .\Cliente.exe
  ```
- **Linux (Debian/Ubuntu):**
  ```bash
  g++ -o Cliente Cliente.cpp
  ./Cliente
  ```
- **macOS:**
  ```bash
  g++ -o Cliente Cliente.cpp -std=c++11
  ./Cliente
  ```

---

## 📦 Catálogo de Códigos de Barra para Pruebas en Vivo

Utiliza estos códigos numéricos en la interfaz web o en la consola de C++ para validar la respuesta inmediata del sistema y el flujo distribuido:

- **Categoría Bodega:**
  - Code: `750101` -> Leche Evaporada Gloria Azul 400g (Precio: S/. 4.20 | Stock: 150)
  - Code: `750105` -> Papas Lays Clásicas Familiares (Precio: S/. 6.50 | Stock: 75)
- **Categoría Bebidas/Licores:**
  - Code: `750201` -> Gaseosa Inca Kola Botella 1.5L (Precio: S/. 7.20 | Stock: 80)
  - Code: `750206` -> Whisky Johnnie Walker Black Label (Precio: S/. 135.00 | Stock: 55)
- **Categoría Librería:**
  - Code: `750301` -> Cuaderno Standford Quadriculado A4 (Precio: S/. 7.50 | Stock: 120)
  - Code: `750302` -> Lapicero Faber-Castell Trilux 032 (Precio: S/. 1.20 | Stock: 500)

---

## 🌐 Configuración de Red para Entornos Reales (Cambio de IP)

Al pasar de un entorno local (`localhost`) a una red distribuida real (como el laboratorio de la universidad), se debe actualizar la dirección IP del servidor central en los clientes externos (la caja C++ y el frontend web no corren dentro de Docker, así que necesitan la IP real del host).

### 1. Obtener la IP del Servidor (Debian)

En la terminal de tu máquina con Debian, ejecuta el siguiente comando para conocer la IP asignada:

```bash
ip a
```

(Identifica la dirección IPv4 al lado de `inet`, por ejemplo: `192.168.1.50`).

### 2. Actualizar el Cliente C++ (Cliente.cpp)

Antes de compilar la caja física, abre `Cliente.cpp` y reemplaza la IP local por la IP de tu servidor Debian:

```cpp
serverAddr.sin_addr.s_addr = inet_addr("192.168.1.50"); // Aquí pones la IP de Debian
```

### 3. Actualizar la Interfaz Web (index.html)

En las funciones asíncronas de JavaScript, actualiza las URL de los llamados `fetch` para apuntar al puerto del Gateway en el servidor:

```js
fetch("http://192.168.1.50:8080/api/productos/" + codigo);
```

---

## 🧩 Alternativa: Ejecución Manual sin Docker

Si prefieres no usar Docker (por ejemplo para depurar con tu IDE), puedes seguir el flujo clásico:

### Requisitos adicionales para esta vía

- **Base de Datos:** MySQL Server o MariaDB (vía XAMPP en Windows o nativo en Linux/macOS).
- **Backend:** Java JDK 17 o superior.

### 1. Configurar la Base de Datos (MySQL)

```bash
mysql -u root -e "CREATE DATABASE IF NOT EXISTS autoservicio_green;"
mysql -u root autoservicio_green < autoservicio-backend/autoservicio_green.sql
```

(En Windows CMD/PowerShell, puedes abrir tu panel de phpMyAdmin, crear la base de datos `autoservicio_green` e importar el archivo manualmente desde la carpeta `autoservicio-backend/`).

Otorga los privilegios necesarios al usuario `root`:

```sql
ALTER USER 'root'@'localhost' IDENTIFIED BY '';
GRANT ALL PRIVILEGES ON *.* TO 'root'@'localhost' WITH GRANT OPTION;
FLUSH PRIVILEGES;
EXIT;
```

### 2. Levantar el Backend Modular (Java)

```bash
cd autoservicio-backend
```

Abre 3 terminales independientes para inicializar los microservicios en este orden:

**Terminal 1 — Inventario (Puerto 8081):**

```bash
# Windows
.\gradlew.bat :ms-inventario:run
# Linux/macOS
chmod +x gradlew
./gradlew :ms-inventario:run
```

**Terminal 2 — Ventas (Puerto 8082):**

```bash
# Windows
.\gradlew.bat :ms-ventas:run
# Linux/macOS
./gradlew :ms-ventas:run
```

**Terminal 3 — Gateway (Puerto HTTP 8080):**

```bash
# Windows
.\gradlew.bat :ms-gateway:run
# Linux/macOS
./gradlew :ms-gateway:run
```

> Si también quieres correr `ms-consola` de forma manual (cuarta terminal), apunta sus variables de entorno a `localhost`:
>
> ```bash
> # Linux/macOS
> MS_INVENTARIO_HOST=localhost MS_VENTAS_HOST=localhost ./gradlew :ms-consola:run
> ```

Luego sigue con el **Paso 6** (Frontend) y **Paso 7** (Caja C++) descritos arriba.
