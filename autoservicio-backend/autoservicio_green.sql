SET FOREIGN_KEY_CHECKS = 0;
CREATE DATABASE IF NOT EXISTS autoservicio_green;
USE autoservicio_green;

-- 1. ESTRUCTURA DE LA TABLA: productos
DROP TABLE IF EXISTS `productos`;
CREATE TABLE `productos` (
  `id` INT AUTO_INCREMENT PRIMARY KEY,
  `codigo_barras` VARCHAR(50) NOT NULL UNIQUE,
  `nombre` VARCHAR(100) NOT NULL,
  `precio` DECIMAL(10,2) NOT NULL,
  `stock` INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 2. ESTRUCTURA DE LA TABLA: ventas_cabecera
DROP TABLE IF EXISTS `ventas_cabecera`;
CREATE TABLE `ventas_cabecera` (
  `id` INT AUTO_INCREMENT PRIMARY KEY,
  `caja_id` VARCHAR(50) NOT NULL,
  `total` DECIMAL(10,2) NOT NULL,
  `fecha_hora` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP()
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 3. ESTRUCTURA DE LA TABLA: ventas_detalle
DROP TABLE IF EXISTS `ventas_detalle`;
CREATE TABLE `ventas_detalle` (
  `id` INT AUTO_INCREMENT PRIMARY KEY,
  `venta_id` INT NOT NULL,
  `producto_id` INT NOT NULL,
  `cantidad` INT NOT NULL,
  `precio_unitario` DECIMAL(10,2) NOT NULL,
  `subtotal` DECIMAL(10,2) NOT NULL,
  CONSTRAINT `ventas_detalle_ibfk_1` FOREIGN KEY (`venta_id`) REFERENCES `ventas_cabecera` (`id`) ON DELETE CASCADE,
  CONSTRAINT `ventas_detalle_ibfk_2` FOREIGN KEY (`producto_id`) REFERENCES `productos` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 4. ESTRUCTURA DE LA TABLA: turnos_corte
DROP TABLE IF EXISTS `turnos_corte`;
CREATE TABLE `turnos_corte` (
  `id` INT AUTO_INCREMENT PRIMARY KEY,
  `boleta_base_id` INT NOT NULL,
  `fecha_corte` DATETIME DEFAULT CURRENT_TIMESTAMP()
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;


-- 5. CARGA DEL CATÁLOGO MULTISECTORIAL (Ampliado a 30 productos con Stock > 50)
INSERT INTO `productos` (`id`, `codigo_barras`, `nombre`, `precio`, `stock`) VALUES 
-- ── SECCIÓN 1: BODEGA Y ABARROTES (Códigos 7501xx)
(1, '750101', 'Leche Evaporada Gloria Azul 400g', 4.20, 150),
(2, '750102', 'Arroz Costeño Extra Bolsa 1kg', 4.80, 200),
(3, '750103', 'Fideos Lavaggi Espagueti 450g', 3.10, 120),
(4, '750104', 'Aceite Vegetal Primor Premium 1L', 11.50, 85),
(5, '750105', 'Papas Lays Clásicas Familiares', 6.50, 75),
(6, '750106', 'Doritos Mega Queso Familiar', 6.80, 70),
(7, '750107', 'Atún en Trozos Campomar 170g', 5.80, 140),
(8, '750108', 'Café Altomayo Clásico Frasco 180g', 15.50, 65),
(9, '750109', 'Azúcar Rubia Cartavio Bolsa 1kg', 4.50, 180),
(10, '750110', 'Galletas Casino de Menta Pack x6', 4.00, 220),

-- ── SECCIÓN 2: BEBIDAS Y LICORES (Códigos 7502xx)
(11, '750201', 'Gaseosa Inca Kola Botella 1.5L', 7.20, 80),
(12, '750202', 'Gaseosa Coca Cola Sin Azúcar 1.5L', 7.20, 85),
(13, '750203', 'Pisco Queirolo Quebranta 750ml', 38.50, 60),
(14, '750204', 'Ron Cartavio Superior Añejo 1L', 45.00, 65),
(15, '750205', 'Cerveza Pilsen Callao Botella 630ml', 6.50, 115),
(16, '750206', 'Whisky Johnnie Walker Black Label 750ml', 135.00, 55),
(17, '750207', 'Cerveza Cusqueña Dorada Botella 620ml', 7.50, 90),
(18, '750208', 'Vodka Smirnoff Red 750ml', 39.90, 70),
(19, '750209', 'Gin Tanqueray London Dry 750ml', 89.00, 52),
(20, '750210', 'Cerveza Corona Extra Botella 355ml', 8.50, 110),

-- ── SECCIÓN 3: MINILIBRERÍA (Códigos 7503xx)
(21, '750301', 'Cuaderno Standford Quadriculado A4', 7.50, 120),
(22, '750302', 'Lapicero Faber-Castell Trilux 032 Azul', 1.20, 500),
(23, '750303', 'Plumones Faber-Castell Fiesta x12', 14.50, 60),
(24, '750304', 'Corrector Líquido Artesco en Cinta', 4.50, 95),
(25, '750305', 'Goma en Barra Glue Stick Uhu 40g', 6.20, 110),
(26, '750306', 'Tijera Escolar Artesco Soft Grip', 3.80, 80),
(27, '750307', 'Folder Manila Utilex Tamaño A4', 0.80, 450),
(28, '750308', 'Resaltador Faber-Castell 46 Amarillo', 3.50, 130),

-- ── SECCIÓN 4: COMPLEMENTOS MULTIPROPÓSITO (Códigos 7504xx)
(29, '750401', 'Bebida Energizante Red Bull 250ml', 8.50, 55),
(30, '750402', 'Agua San Luis Sin Gas 1L', 2.50, 190);
SET FOREIGN_KEY_CHECKS = 1;