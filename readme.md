# 🌿 Minimarket Autoservicio - Green

Sistema distribuido multiservicio de arquitectura modular que integra un backend en Java (Gradle) con comunicación híbrida (Sockets TCP y HTTP REST), una interfaz de caja registradora nativa en C++ y un frontend web asíncrono para el escaneo de productos en ráfaga (Abarrotes, Bebidas/Licores y Minilibrería).

---

## 🛠️ Requisitos Previos

Antes de iniciar, asegúrate de tener instalado lo siguiente según tu sistema operativo:

- **Base de Datos:** MySQL Server o MariaDB (vía XAMPP en Windows o nativo en Linux/macOS).
- **Backend:** Java JDK 17 o superior.
- **Caja (C++):** Un compilador de C++ (g++ / MinGW para Windows, build-essential en Linux, o Clang en macOS).

---

## 🚀 Guía de Despliegue Paso a Paso

### Paso 1: Configurar la Base de Datos (MySQL)

1. Abre tu terminal o consola de comandos e inicia sesión en MySQL como usuario administrador:
   mysql -u root

2. Ejecuta el script de inicialización limpio incluido en el repositorio para estructurar las tablas y cargar los 30 productos comerciales de prueba (con stocks iniciales completos y sin historial viejo de boletas):

   # Linux / macOS / Windows (Git Bash)

   mysql -u root -e "CREATE DATABASE IF NOT EXISTS autoservicio_green;"
   mysql -u root autoservicio_green < autoservicio-backend/autoservicio_green.sql

   (En Windows CMD/PowerShell, puedes abrir tu panel de phpMyAdmin, crear la base de datos autoservicio_green e importar el archivo manualmente desde la carpeta autoservicio-backend/).

---

## 🛠️ Configuración Inicial de la Base de Datos

Antes de inicializar los microservicios, se deben otorgar los privilegios necesarios al usuario `root` en MariaDB/MySQL para permitir la conexión del backend:

ALTER USER 'root'@'localhost' IDENTIFIED BY '';
GRANT ALL PRIVILEGES ON _._ TO 'root'@'localhost' WITH GRANT OPTION;
FLUSH PRIVILEGES;
EXIT;

### Paso 2: Levantar el Backend Modular (Java)

Navega hasta la carpeta raíz del servidor central:
cd autoservicio-backend

Debes abrir 3 terminales independientes (o pestañas) para inicializar los microservicios en el siguiente orden estricto:

#### Pestaña 1: Servicio de Inventario (Puerto 8081)

- Windows (CMD/PowerShell):
  .\gradlew.bat :ms-inventario:run
- Linux / macOS:
  chmod +x gradlew
  ./gradlew :ms-inventario:run

#### Pestaña 2: Servicio de Ventas (Puerto 8082)

- Windows (CMD/PowerShell):
  .\gradlew.bat :ms-ventas:run
- Linux / macOS:
  ./gradlew :ms-ventas:run

#### Pestaña 3: Gateway Orquestador Central (Puerto HTTP 8080 / TCP 12345)

- Windows (CMD/PowerShell):
  .\gradlew.bat :ms-gateway:run
- Linux / macOS:
  ./gradlew :ms-gateway:run

---

### Paso 3: Lanzar la Interfaz de Escaneo Web (Frontend)

El frontend se comunica con el Gateway de manera asíncrona.

1. Navega a la carpeta correspondiente o ubica el archivo de la interfaz.
2. Abre el archivo index.html directamente en cualquier navegador web moderno (Chrome, Edge, Firefox, Safari).
3. Utiliza la barra de entrada para simular las ráfagas de escaneo de productos mediante los códigos de barra del catálogo oficial.

---

### Paso 4: Compilar y Ejecutar la Caja Registradora (C++)

Navega a la carpeta de la caja física:
cd autoservicio-caja

Compila y ejecuta el binario nativo según tu sistema operativo:

- En Windows (MinGW / GCC):
  g++ -o Cliente.exe Cliente.cpp -lws2_32
  .\Cliente.exe
- En Linux (Debian/Ubuntu):
  g++ -o Cliente Cliente.cpp
  ./Cliente
- En macOS:
  g++ -o Cliente Cliente.cpp -std=c++11
  ./Cliente

---

## 📦 Catálogo de Códigos de Barra para Pruebas en Vivo

Utiliza estos códigos numéricos en la interfaz web o en la consola de C++ para validar la respuesta inmediata del sistema y el flujo distribuido:

- Categoría Bodega:
  - Code: 750101 -> Leche Evaporada Gloria Azul 400g (Precio: S/. 4.20 | Stock: 150)
  - Code: 750105 -> Papas Lays Clásicas Familiares (Precio: S/. 6.50 | Stock: 75)
- Categoría Bebidas/Licores:
  - Code: 750201 -> Gaseosa Inca Kola Botella 1.5L (Precio: S/. 7.20 | Stock: 80)
  - Code: 750206 -> Whisky Johnnie Walker Black Label (Precio: S/. 135.00 | Stock: 55)
- Categoría Librería:
  - Code: 750301 -> Cuaderno Standford Quadriculado A4 (Precio: S/. 7.50 | Stock: 120)
  - Code: 750302 -> Lapicero Faber-Castell Trilux 032 (Precio: S/. 1.20 | Stock: 500)

---

## 🌐 Configuración de Red para Entornos Reales (Cambio de IP)

Al pasar de un entorno local (localhost) a una red distribuida real (como el laboratorio de la universidad), se debe actualizar la dirección IP del servidor central en los clientes.

### 1. Obtener la IP del Servidor (Debian)

En la terminal de tu máquina con Debian, ejecuta el siguiente comando para conocer la IP asignada:
ip a
(Identifica la dirección IPv4 al lado de inet, por ejemplo: 192.168.1.50).

### 2. Actualizar el Cliente C++ (Cliente.cpp)

Antes de compilar la caja física, abre Cliente.cpp y reemplaza la IP local por la IP de tu servidor Debian:
serverAddr.sin_addr.s_addr = inet_addr("192.168.1.50"); // Aquí pones la IP de Debian

### 3. Actualizar la Interfaz Web (index.html)

En las funciones asíncronas de JavaScript, actualiza las URL de los llamados fetch para apuntar al puerto del Gateway en el servidor:
fetch('http://192.168.1.50:8080/api/productos/' + codigo)
