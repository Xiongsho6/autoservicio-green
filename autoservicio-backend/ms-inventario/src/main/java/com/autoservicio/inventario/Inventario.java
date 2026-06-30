package com.autoservicio.inventario;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Logger;

// ═══════════════════════════════════════════════════════════════════════════
//  MS-INVENTARIO  —  Refactorizado (todo en un solo archivo)
//
//  Cambios v2:
//   - Menú se limpia antes de cada redibujado (opción C)
//   - Cola thread-safe logVentas acumula hasta 50 movimientos en memoria
//   - Los logs de venta ya NO salen directo a stdout (no contaminan el menú)
//   - Nueva opción [8] Ver Últimas Ventas Procesadas (opción D)
// ═══════════════════════════════════════════════════════════════════════════

public class Inventario {

    // ── Cola compartida de log de ventas ──────────────────────────────────
    // Thread-safe: el hilo del servidor TCP escribe, el hilo del menú lee.
    // Máximo 50 entradas; las más antiguas se descartan automáticamente.
    static final ConcurrentLinkedDeque<String> logVentas = new ConcurrentLinkedDeque<>();

    static void registrarLogVenta(String linea) {
        logVentas.addFirst(linea);
        if (logVentas.size() > 50)
            logVentas.pollLast();
    }

    public static void main(String[] args) {
        ConnectionPool.init();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[INFO] Apagando MS-INVENTARIO...");
            ConnectionPool.shutdown();
        }));

        // Menú del supervisor en hilo daemon
        Thread menuThread = new Thread(SupervisorMenu::iniciar);
        menuThread.setDaemon(true);
        menuThread.start();

        // Hilo principal: escuchar al Gateway
        InventarioServer.iniciar();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MODELO
    // ═══════════════════════════════════════════════════════════════════════

    static class Producto {
        final int id;
        final String codigoBarras;
        final String nombre;
        final double precio;
        final int stock;

        Producto(int id, String codigoBarras, String nombre, double precio, int stock) {
            this.id = id;
            this.codigoBarras = codigoBarras;
            this.nombre = nombre;
            this.precio = precio;
            this.stock = stock;
        }
    }

    static class FilaStock {
        final int id;
        final String codigoBarras;
        final String nombre;
        final double precio;
        final int stock;
        final String alerta;

        FilaStock(int id, String codigoBarras, String nombre, double precio, int stock) {
            this.id = id;
            this.codigoBarras = codigoBarras;
            this.nombre = nombre;
            this.precio = precio;
            this.stock = stock;
            this.alerta = stock == 0 ? "AGOTADO" : stock <= 5 ? "BAJO" : "";
        }
    }

    static class FilaMovimiento {
        final int boletaId;
        final String cajaId;
        final int cantidad;
        final double precioUnitario;
        final double subtotal;
        final String fechaHora;

        FilaMovimiento(int boletaId, String cajaId, int cantidad,
                double precioUnitario, double subtotal, String fechaHora) {
            this.boletaId = boletaId;
            this.cajaId = cajaId;
            this.cantidad = cantidad;
            this.precioUnitario = precioUnitario;
            this.subtotal = subtotal;
            this.fechaHora = fechaHora;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CONFIG — Pool de conexiones HikariCP
    // ═══════════════════════════════════════════════════════════════════════

    static final class ConnectionPool {
        private static final Logger LOG = Logger.getLogger("ConnectionPool");
        private static HikariDataSource dataSource;

        private ConnectionPool() {
        }

        static void init() {
            Properties props = new Properties();
            try (InputStream is = ConnectionPool.class
                    .getClassLoader()
                    .getResourceAsStream("db.properties")) {
                if (is != null) {
                    props.load(is);
                } else {
                    LOG.warning("[DB] db.properties no encontrado — usando credenciales por defecto.");
                    props.setProperty("db.url",
                            "jdbc:mysql://localhost:3306/autoservicio_green?useSSL=false&serverTimezone=UTC");
                    props.setProperty("db.user", "root");
                    props.setProperty("db.password", "");
                }
            } catch (IOException e) {
                throw new IllegalStateException("Error al leer db.properties: " + e.getMessage());
            }

            // [DOCKER] Variables de entorno tienen prioridad sobre db.properties.
            // Esto permite correr el mismo JAR en local (lee db.properties) o en
            // Docker/Kubernetes (lee env vars inyectadas por docker-compose o el Deployment).
            String envUrl = System.getenv("DB_URL");
            String envUser = System.getenv("DB_USER");
            String envPassword = System.getenv("DB_PASSWORD");

            if (envUrl != null && !envUrl.isBlank()) {
                props.setProperty("db.url", envUrl);
                LOG.info("[DB] Usando DB_URL desde variable de entorno.");
            }
            if (envUser != null && !envUser.isBlank()) {
                props.setProperty("db.user", envUser);
            }
            if (envPassword != null) {
                props.setProperty("db.password", envPassword);
            }

            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(props.getProperty("db.url"));
            cfg.setUsername(props.getProperty("db.user"));
            cfg.setPassword(props.getProperty("db.password"));
            cfg.setMaximumPoolSize(Integer.parseInt(props.getProperty("db.pool.max", "10")));
            cfg.setMinimumIdle(Integer.parseInt(props.getProperty("db.pool.min", "2")));
            cfg.setIdleTimeout(Long.parseLong(props.getProperty("db.pool.idle", "30000")));
            cfg.setConnectionTestQuery("SELECT 1");

            dataSource = new HikariDataSource(cfg);
            LOG.info("[DB] Pool HikariCP listo — máx " + cfg.getMaximumPoolSize() + " conexiones.");
        }

        static Connection obtenerConexion() throws Exception {
            if (dataSource == null)
                throw new IllegalStateException("Pool no inicializado.");
            return dataSource.getConnection();
        }

        static void shutdown() {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
                LOG.info("[DB] Pool HikariCP cerrado.");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // REPOSITORY — Todas las queries SQL
    // ═══════════════════════════════════════════════════════════════════════

    static final class InventarioRepository {

        private InventarioRepository() {
        }

        static List<FilaStock> obtenerStockCompleto() throws Exception {
            final String sql = "SELECT id, codigo_barras, nombre, precio, stock FROM productos ORDER BY nombre ASC";
            List<FilaStock> resultado = new ArrayList<>();
            try (Connection con = ConnectionPool.obtenerConexion();
                    PreparedStatement ps = con.prepareStatement(sql);
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    resultado.add(new FilaStock(
                            rs.getInt("id"),
                            rs.getString("codigo_barras"),
                            rs.getString("nombre"),
                            rs.getDouble("precio"),
                            rs.getInt("stock")));
                }
            }
            return resultado;
        }

        static List<FilaStock> obtenerProductosBajoStock(int umbral) throws Exception {
            final String sql = "SELECT id, codigo_barras, nombre, precio, stock "
                    + "FROM productos WHERE stock <= ? ORDER BY stock ASC";
            List<FilaStock> resultado = new ArrayList<>();
            try (Connection con = ConnectionPool.obtenerConexion();
                    PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, umbral);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        resultado.add(new FilaStock(
                                rs.getInt("id"),
                                rs.getString("codigo_barras"),
                                rs.getString("nombre"),
                                rs.getDouble("precio"),
                                rs.getInt("stock")));
                    }
                }
            }
            return resultado;
        }

        static FilaStock buscarPorCodigo(String codigoBarras) throws Exception {
            final String sql = "SELECT id, codigo_barras, nombre, precio, stock FROM productos WHERE codigo_barras = ?";
            try (Connection con = ConnectionPool.obtenerConexion();
                    PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, codigoBarras);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new FilaStock(
                                rs.getInt("id"),
                                rs.getString("codigo_barras"),
                                rs.getString("nombre"),
                                rs.getDouble("precio"),
                                rs.getInt("stock"));
                    }
                }
            }
            return null;
        }

        static List<FilaMovimiento> obtenerHistorialPorProducto(int productoId) throws Exception {
            final String sql = "SELECT vd.venta_id, vc.caja_id, vd.cantidad, vd.precio_unitario, vd.subtotal, vc.fecha_hora "
                    + "FROM ventas_detalle vd "
                    + "JOIN ventas_cabecera vc ON vd.venta_id = vc.id "
                    + "WHERE vd.producto_id = ? "
                    + "ORDER BY vc.fecha_hora DESC";
            List<FilaMovimiento> resultado = new ArrayList<>();
            try (Connection con = ConnectionPool.obtenerConexion();
                    PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, productoId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        resultado.add(new FilaMovimiento(
                                rs.getInt("venta_id"),
                                rs.getString("caja_id"),
                                rs.getInt("cantidad"),
                                rs.getDouble("precio_unitario"),
                                rs.getDouble("subtotal"),
                                rs.getString("fecha_hora")));
                    }
                }
            }
            return resultado;
        }

        static void obtenerResumenPorCaja(int productoId, List<String[]> out) throws Exception {
            final String sql = "SELECT vc.caja_id, SUM(vd.cantidad) AS total_vendido, SUM(vd.subtotal) AS total_monto "
                    + "FROM ventas_detalle vd "
                    + "JOIN ventas_cabecera vc ON vd.venta_id = vc.id "
                    + "WHERE vd.producto_id = ? "
                    + "GROUP BY vc.caja_id ORDER BY total_vendido DESC";
            try (Connection con = ConnectionPool.obtenerConexion();
                    PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, productoId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new String[] {
                                rs.getString("caja_id"),
                                String.valueOf(rs.getInt("total_vendido")),
                                String.format("%.2f", rs.getDouble("total_monto"))
                        });
                    }
                }
            }
        }

        static int agregarProducto(String codigoBarras, String nombre, double precio, int stockInicial)
                throws Exception {
            final String sql = "INSERT INTO productos (codigo_barras, nombre, precio, stock) VALUES (?, ?, ?, ?)";
            try (Connection con = ConnectionPool.obtenerConexion();
                    PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, codigoBarras);
                ps.setString(2, nombre);
                ps.setDouble(3, precio);
                ps.setInt(4, stockInicial);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next())
                        return keys.getInt(1);
                }
            }
            throw new Exception("No se generó ID al insertar el producto.");
        }

        static void reabastecer(int productoId, int cantidad) throws Exception {
            final String sql = "UPDATE productos SET stock = stock + ? WHERE id = ?";
            try (Connection con = ConnectionPool.obtenerConexion();
                    PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, cantidad);
                ps.setInt(2, productoId);
                int filas = ps.executeUpdate();
                if (filas == 0)
                    throw new Exception("Producto ID " + productoId + " no encontrado.");
            }
        }

        static JsonObject consultarProductos(JsonArray productosArr) throws Exception {
            JsonObject resp = new JsonObject();
            JsonArray detalles = new JsonArray();
            try (Connection con = ConnectionPool.obtenerConexion()) {
                for (JsonElement elem : productosArr) {
                    JsonObject item = elem.getAsJsonObject();
                    String codigo = item.get("codigo").getAsString();
                    int cantidad = item.get("cantidad").getAsInt();

                    final String sql = "SELECT id, nombre, precio, stock FROM productos WHERE codigo_barras = ?";
                    try (PreparedStatement ps = con.prepareStatement(sql)) {
                        ps.setString(1, codigo);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                resp.addProperty("status", "ERROR");
                                resp.addProperty("mensaje", "Código '" + codigo + "' no existe.");
                                return resp;
                            }
                            int stockActual = rs.getInt("stock");
                            if (stockActual < cantidad) {
                                resp.addProperty("status", "ERROR");
                                resp.addProperty("mensaje", "Stock insuficiente para: "
                                        + rs.getString("nombre") + " (Quedan: " + stockActual + ")");
                                return resp;
                            }
                            JsonObject det = new JsonObject();
                            det.addProperty("id", rs.getInt("id"));
                            det.addProperty("nombre", rs.getString("nombre"));
                            det.addProperty("precio", rs.getDouble("precio"));
                            det.addProperty("cantidad", cantidad);
                            detalles.add(det);
                        }
                    }
                }
            }
            resp.addProperty("status", "OK");
            resp.add("detalles", detalles);
            return resp;
        }

        static JsonObject procesarVenta(JsonArray productosArr) throws Exception {
            JsonObject resp = new JsonObject();
            try (Connection con = ConnectionPool.obtenerConexion()) {
                con.setAutoCommit(false);
                try {
                    JsonArray detalles = new JsonArray();
                    StringBuilder logBoleta = new StringBuilder();

                    for (JsonElement elem : productosArr) {
                        JsonObject item = elem.getAsJsonObject();
                        String codigo = item.get("codigo").getAsString();
                        int cantSolicitada = item.get("cantidad").getAsInt();

                        if (cantSolicitada <= 0 || cantSolicitada > 999) {
                            throw new IllegalArgumentException(
                                    "Cantidad inválida (" + cantSolicitada + ") para: " + codigo);
                        }

                        final String sqlSel = "SELECT id, nombre, precio, stock FROM productos WHERE codigo_barras = ? FOR UPDATE";
                        try (PreparedStatement ps = con.prepareStatement(sqlSel)) {
                            ps.setString(1, codigo);
                            try (ResultSet rs = ps.executeQuery()) {
                                if (!rs.next())
                                    throw new Exception("Código '" + codigo + "' no existe.");

                                int id = rs.getInt("id");
                                String nombre = rs.getString("nombre");
                                double precio = rs.getDouble("precio");
                                int stockActual = rs.getInt("stock");

                                if (stockActual < cantSolicitada) {
                                    throw new Exception("Stock insuficiente para: " + nombre
                                            + " (Quedan: " + stockActual + ")");
                                }

                                // ── Guardar en cola de log en lugar de imprimir directo ──
                                String lineaLog = String.format(
                                        "%-26s | Stock previo: %-4d | Vendido: %-4d | Stock nuevo: %d",
                                        nombre.length() > 25 ? nombre.substring(0, 25) : nombre,
                                        stockActual,
                                        cantSolicitada,
                                        stockActual - cantSolicitada);
                                logBoleta.append(lineaLog).append("\n");

                                final String sqlUpd = "UPDATE productos SET stock = ? WHERE id = ?";
                                try (PreparedStatement psUpd = con.prepareStatement(sqlUpd)) {
                                    psUpd.setInt(1, stockActual - cantSolicitada);
                                    psUpd.setInt(2, id);
                                    psUpd.executeUpdate();
                                }

                                JsonObject det = new JsonObject();
                                det.addProperty("id", id);
                                det.addProperty("nombre", nombre);
                                det.addProperty("precio", precio);
                                det.addProperty("cantidad", cantSolicitada);
                                detalles.add(det);
                            }
                        }
                    }

                    con.commit();

                    // ── Registrar el bloque completo de la boleta en la cola ──
                    String timestamp = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss")
                            .format(new java.util.Date());
                    String encabezado = "── Boleta procesada " + timestamp + " ──────────────────────";
                    registrarLogVenta(encabezado);
                    // Registrar cada línea del detalle de la boleta
                    for (String linea : logBoleta.toString().split("\n")) {
                        if (!linea.isBlank())
                            registrarLogVenta("  " + linea);
                    }

                    resp.addProperty("status", "OK");
                    resp.add("detalles", detalles);

                } catch (Exception e) {
                    con.rollback();
                    String timestamp = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss")
                            .format(new java.util.Date());
                    registrarLogVenta("── ROLLBACK " + timestamp + " — " + e.getMessage());
                    resp.addProperty("status", "ERROR");
                    resp.addProperty("mensaje", e.getMessage());
                }
            }
            return resp;
        }

        static JsonObject compensarStock(JsonArray productosArr) throws Exception {
            JsonObject resp = new JsonObject();
            try (Connection con = ConnectionPool.obtenerConexion()) {
                con.setAutoCommit(false);
                try {
                    for (JsonElement elem : productosArr) {
                        JsonObject item = elem.getAsJsonObject();
                        int idProducto = item.get("id_directo").getAsInt();
                        int cantidadDevolver = item.get("devolver_cantidad").getAsInt();

                        final String sql = "UPDATE productos SET stock = stock + ? WHERE id = ?";
                        try (PreparedStatement ps = con.prepareStatement(sql)) {
                            ps.setInt(1, cantidadDevolver);
                            ps.setInt(2, idProducto);
                            ps.executeUpdate();
                        }
                        registrarLogVenta(String.format("[SAGA] Stock restaurado — ProdID: %d  (+%d uds.)",
                                idProducto, cantidadDevolver));
                    }
                    con.commit();
                    registrarLogVenta("[SAGA] Compensación completada exitosamente.");
                    resp.addProperty("status", "OK");

                } catch (Exception e) {
                    con.rollback();
                    registrarLogVenta("[SAGA CRITICO] Fallo al compensar stock: " + e.getMessage());
                    resp.addProperty("status", "ERROR");
                    resp.addProperty("mensaje", "Fallo en compensación: " + e.getMessage());
                }
            }
            return resp;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SERVICE — Lógica de negocio y validación
    // ═══════════════════════════════════════════════════════════════════════

    static final class InventarioService {

        private static final Logger LOG = Logger.getLogger("InventarioService");

        private InventarioService() {
        }

        static JsonObject procesarPeticion(String jsonRaw) {
            try {
                JsonObject req = JsonParser.parseString(jsonRaw).getAsJsonObject();
                String accion = req.has("accion") ? req.get("accion").getAsString() : "VENDER";

                if ("COMPENSAR".equals(accion)) {
                    return InventarioRepository.compensarStock(req.getAsJsonArray("productos"));
                } else if ("CONSULTAR".equals(accion)) {
                    return InventarioRepository.consultarProductos(req.getAsJsonArray("productos"));
                } else if ("ADMIN".equals(accion)) {
                    String opcion = req.has("opcion") ? req.get("opcion").getAsString() : "";
                    List<String> inputs = new ArrayList<>();
                    if (req.has("inputs")) {
                        for (JsonElement el : req.getAsJsonArray("inputs")) {
                            inputs.add(el.getAsString());
                        }
                    }
                    String salida = SupervisorMenu.ejecutarOpcionRemota(opcion, inputs);
                    JsonObject resp = new JsonObject();
                    resp.addProperty("status", "OK");
                    resp.addProperty("salida", salida);
                    return resp;
                } else {
                    return InventarioRepository.procesarVenta(req.getAsJsonArray("productos"));
                }
            } catch (Exception e) {
                LOG.severe("[SERVICIO] Error procesando petición: " + e.getMessage());
                JsonObject err = new JsonObject();
                err.addProperty("status", "ERROR");
                err.addProperty("mensaje", "Error interno: " + e.getMessage());
                return err;
            }
        }

        static List<FilaStock> obtenerStockCompleto() throws Exception {
            return InventarioRepository.obtenerStockCompleto();
        }

        static List<FilaStock> obtenerProductosBajoStock(int umbral) throws Exception {
            return InventarioRepository.obtenerProductosBajoStock(umbral);
        }

        static FilaStock buscarPorCodigo(String codigo) throws Exception {
            return InventarioRepository.buscarPorCodigo(codigo);
        }

        static List<FilaMovimiento> obtenerHistorialPorProducto(int productoId) throws Exception {
            return InventarioRepository.obtenerHistorialPorProducto(productoId);
        }

        static void obtenerResumenPorCaja(int productoId, List<String[]> out) throws Exception {
            InventarioRepository.obtenerResumenPorCaja(productoId, out);
        }

        static int agregarProducto(String codigo, String nombre, double precio, int stock) throws Exception {
            if (codigo == null || codigo.isBlank())
                throw new IllegalArgumentException("El código de barras no puede estar vacío.");
            if (nombre == null || nombre.isBlank())
                throw new IllegalArgumentException("El nombre no puede estar vacío.");
            if (precio <= 0)
                throw new IllegalArgumentException("El precio debe ser mayor a 0.");
            if (stock < 0)
                throw new IllegalArgumentException("El stock no puede ser negativo.");
            if (InventarioRepository.buscarPorCodigo(codigo) != null)
                throw new IllegalArgumentException("Ya existe un producto con código: " + codigo);

            return InventarioRepository.agregarProducto(codigo, nombre, precio, stock);
        }

        static void reabastecer(int productoId, int cantidad) throws Exception {
            if (cantidad <= 0)
                throw new IllegalArgumentException("La cantidad a reabastecer debe ser mayor a 0.");
            InventarioRepository.reabastecer(productoId, cantidad);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SERVER — Acepta conexiones TCP del Gateway
    // ═══════════════════════════════════════════════════════════════════════

    static final class InventarioServer {

        private static final int PUERTO = 8081;
        private static final Logger LOG = Logger.getLogger("InventarioServer");

        private InventarioServer() {
        }

        static void iniciar() {
            System.out.println("=== MS-INVENTARIO ACTIVO [Puerto " + PUERTO + "] ===");
            try (ServerSocket serverSocket = new ServerSocket(PUERTO)) {
                while (true) {
                    Socket socket = serverSocket.accept();
                    new Thread(() -> manejarConexion(socket)).start();
                }
            } catch (Exception e) {
                LOG.severe("[SERVER] Error fatal: " + e.getMessage());
            }
        }

        private static void manejarConexion(Socket socket) {
            try (
                    DataInputStream entrada = new DataInputStream(socket.getInputStream());
                    DataOutputStream salida = new DataOutputStream(socket.getOutputStream())) {
                int longitud = entrada.readInt();
                byte[] buffer = new byte[longitud];
                entrada.readFully(buffer);
                String jsonRaw = new String(buffer, "UTF-8");

                JsonObject respuesta = InventarioService.procesarPeticion(jsonRaw);

                byte[] respBytes = respuesta.toString().getBytes("UTF-8");
                salida.writeInt(respBytes.length);
                salida.write(respBytes);

            } catch (Exception e) {
                if (!(e instanceof java.io.EOFException)) {
                    LOG.warning("[SERVER] Error en conexión: " + e.getMessage());
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MENU — Consola interactiva del supervisor
    // ═══════════════════════════════════════════════════════════════════════

    static final class SupervisorMenu {

        private SupervisorMenu() {
        }

        // ── Lock para evitar que dos peticiones ADMIN concurrentes pisen
        //     la redirección de System.out al mismo tiempo. ──────────────
        private static final Object LOCK_CONSOLA = new Object();

        // ── Ejecuta remotamente la misma lógica que el menú local,
        //     capturando todo lo impreso y devolviéndolo como texto.
        //     'inputs' simula lo que el supervisor habría tecleado en
        //     consola para esa opción (en el mismo orden que lo pide). ──
        static String ejecutarOpcionRemota(String opcion, List<String> inputs) {
            synchronized (LOCK_CONSOLA) {
                java.io.PrintStream original = System.out;
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                Scanner scanner = new Scanner(String.join("\n", inputs) + "\n");
                try {
                    System.setOut(new java.io.PrintStream(buffer, true, "UTF-8"));
                    switch (opcion) {
                        case "1":
                            mostrarStockCompleto();
                            break;
                        case "2":
                            mostrarBajoStock();
                            break;
                        case "3":
                            buscarProducto(scanner);
                            break;
                        case "4":
                            mostrarHistorialProducto(scanner);
                            break;
                        case "5":
                            agregarProducto(scanner);
                            break;
                        case "6":
                            reabastecerProducto(scanner);
                            break;
                        case "7":
                            mostrarUltimasVentas();
                            break;
                        default:
                            System.out.println("[!] Opcion invalida.");
                    }
                } catch (Exception e) {
                    System.out.println("[ERROR] " + e.getMessage());
                } finally {
                    System.setOut(original);
                }
                try {
                    return buffer.toString("UTF-8");
                } catch (Exception e) {
                    return buffer.toString();
                }
            }
        }

        static void iniciar() {
            Scanner scanner = new Scanner(System.in);
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }

            while (true) {
                if (!scanner.hasNextLine()) {
                    // No hay consola interactiva disponible (ej. contenedor Docker
                    // sin stdin_open/tty). Se detiene el menu sin afectar al resto del servicio.
                    System.out.println("[INFO] Consola interactiva no disponible. Menu de supervisor desactivado.");
                    return;
                }
                // ── Limpiar pantalla antes de redibujar el menú ──────────
                limpiarPantalla();

                System.out.println("==================================================");
                System.out.println("    MENU DE SUPERVISOR - INVENTARIO GREEN         ");
                System.out.println("==================================================");
                System.out.println(" [1] Ver Stock Completo");
                System.out.println(" [2] Ver Productos con Stock Bajo o Agotado");
                System.out.println(" [3] Buscar Producto por Codigo de Barras");
                System.out.println(" [4] Ver Historial de Ventas de un Producto");
                System.out.println(" [5] Agregar Producto Nuevo");
                System.out.println(" [6] Reabastecer Stock de un Producto");
                System.out.println(" [7] Ver Ultimas Ventas Procesadas");
                System.out.println("--------------------------------------------------");
                System.out.print(" Ingrese una opcion: ");

                String opcion = scanner.nextLine().trim();

                switch (opcion) {
                    case "1":
                        mostrarStockCompleto();
                        break;
                    case "2":
                        mostrarBajoStock();
                        break;
                    case "3":
                        buscarProducto(scanner);
                        break;
                    case "4":
                        mostrarHistorialProducto(scanner);
                        break;
                    case "5":
                        agregarProducto(scanner);
                        break;
                    case "6":
                        reabastecerProducto(scanner);
                        break;
                    case "7":
                        mostrarUltimasVentas();
                        break;
                    default:
                        System.out.println("[!] Opcion invalida.");
                }

                // Pausa para que el supervisor pueda leer el resultado
                System.out.println("\n Presione ENTER para volver al menu...");
                scanner.nextLine();
            }
        }

        // ── [1] Stock completo ────────────────────────────────────────────

        private static void mostrarStockCompleto() {
            try {
                List<FilaStock> lista = InventarioService.obtenerStockCompleto();
                imprimirTablaStock("STOCK COMPLETO DEL INVENTARIO", lista);
            } catch (Exception e) {
                System.out.println("[ERROR] " + e.getMessage());
            }
        }

        // ── [2] Stock bajo ────────────────────────────────────────────────

        private static void mostrarBajoStock() {
            try {
                List<FilaStock> lista = InventarioService.obtenerProductosBajoStock(5);
                if (lista.isEmpty()) {
                    System.out.println("\n  [OK] Todos los productos tienen stock suficiente (> 5 unidades).");
                } else {
                    imprimirTablaStock("PRODUCTOS CON STOCK BAJO O AGOTADO (umbral: 5)", lista);
                }
            } catch (Exception e) {
                System.out.println("[ERROR] " + e.getMessage());
            }
        }

        // ── [3] Buscar por código ─────────────────────────────────────────

        private static void buscarProducto(Scanner scanner) {
            System.out.print("\n Ingrese el codigo de barras: ");
            String codigo = scanner.nextLine().trim();
            try {
                FilaStock p = InventarioService.buscarPorCodigo(codigo);
                if (p == null) {
                    System.out.println("  [!] No se encontro ningun producto con codigo: " + codigo);
                } else {
                    System.out.println("\n--------------------------------------------------");
                    System.out.println("  Resultado de busqueda");
                    System.out.println("--------------------------------------------------");
                    System.out.printf("  ID           : %d%n", p.id);
                    System.out.printf("  Codigo       : %s%n", p.codigoBarras);
                    System.out.printf("  Nombre       : %s%n", p.nombre);
                    System.out.printf("  Precio       : S/. %.2f%n", p.precio);
                    System.out.printf("  Stock actual : %d %s%n", p.stock,
                            p.alerta.isEmpty() ? "" : "  ! " + p.alerta);
                    System.out.println("--------------------------------------------------");
                }
            } catch (Exception e) {
                System.out.println("[ERROR] " + e.getMessage());
            }
        }

        // ── [4] Historial de ventas de un producto ────────────────────────

        private static void mostrarHistorialProducto(Scanner scanner) {
            try {
                List<FilaStock> lista = InventarioService.obtenerStockCompleto();
                imprimirTablaStock("PRODUCTOS DISPONIBLES — elige el ID", lista);
            } catch (Exception e) {
                System.out.println("[ERROR] No se pudo cargar la lista: " + e.getMessage());
                return;
            }

            System.out.print("\n Ingrese el ID del producto: ");
            String input = scanner.nextLine().trim();

            int productoId;
            try {
                productoId = Integer.parseInt(input);
            } catch (NumberFormatException e) {
                System.out.println("  [!] Ingrese solo el numero del ID.");
                return;
            }

            try {
                FilaStock producto = null;
                for (FilaStock f : InventarioService.obtenerStockCompleto()) {
                    if (f.id == productoId) {
                        producto = f;
                        break;
                    }
                }

                if (producto == null) {
                    System.out.println("  [!] No existe ningun producto con ID: " + productoId);
                    return;
                }

                System.out.println("\n======================================================================");
                System.out.printf("  HISTORIAL DE VENTAS — %s (ID: %d)%n", producto.nombre, producto.id);
                System.out.println("======================================================================");

                List<String[]> resumenCaja = new ArrayList<>();
                InventarioService.obtenerResumenPorCaja(producto.id, resumenCaja);

                if (!resumenCaja.isEmpty()) {
                    System.out.println("\n  Resumen por terminal de caja:");
                    System.out.printf("  %-22s | %-15s | %s%n", "Caja ID", "Uds. Vendidas", "Monto Total");
                    System.out.println("  ------------------------------------------------------------");
                    for (String[] fila : resumenCaja) {
                        System.out.printf("  %-22s | %-15s | S/. %s%n", fila[0], fila[1], fila[2]);
                    }
                }

                List<FilaMovimiento> movimientos = InventarioService.obtenerHistorialPorProducto(producto.id);

                System.out.println("\n  Detalle por boleta (mas reciente primero):");
                System.out.printf("  %-10s | %-20s | %-8s | %-12s | %-12s | %s%n",
                        "Boleta #", "Caja ID", "Cant.", "Precio Unit.", "Subtotal", "Fecha");
                System.out.println("  --------------------------------------------------------------------------");

                if (movimientos.isEmpty()) {
                    System.out.println("  [!] Este producto no ha sido vendido aun.");
                } else {
                    int totalUnidades = 0;
                    double totalMonto = 0;
                    for (FilaMovimiento m : movimientos) {
                        System.out.printf("  %-10d | %-20s | %-8d | S/. %-8.2f | S/. %-8.2f | %s%n",
                                m.boletaId, m.cajaId, m.cantidad,
                                m.precioUnitario, m.subtotal, m.fechaHora);
                        totalUnidades += m.cantidad;
                        totalMonto += m.subtotal;
                    }
                    System.out.println("  --------------------------------------------------------------------------");
                    System.out.printf("  TOTAL VENDIDO: %d unidades  |  S/. %.2f recaudados%n",
                            totalUnidades, totalMonto);
                }
                System.out.println("======================================================================");

            } catch (Exception e) {
                System.out.println("[ERROR] " + e.getMessage());
            }
        }

        // ── [5] Agregar producto nuevo ────────────────────────────────────

        private static void agregarProducto(Scanner scanner) {
            System.out.println("\n--------------------------------------------------");
            System.out.println("  NUEVO PRODUCTO");
            System.out.println("--------------------------------------------------");

            System.out.print(" Codigo de barras : ");
            String codigo = scanner.nextLine().trim();

            System.out.print(" Nombre           : ");
            String nombre = scanner.nextLine().trim();

            System.out.print(" Precio (S/.)     : ");
            String precioStr = scanner.nextLine().trim();

            System.out.print(" Stock inicial    : ");
            String stockStr = scanner.nextLine().trim();

            try {
                double precio = Double.parseDouble(precioStr.replace(",", "."));
                int stock = Integer.parseInt(stockStr);

                int nuevoId = InventarioService.agregarProducto(codigo, nombre, precio, stock);

                System.out.println("\n  [OK] Producto registrado exitosamente.");
                System.out.printf("  -> ID asignado : #%d%n", nuevoId);
                System.out.printf("  -> Codigo      : %s%n", codigo);
                System.out.printf("  -> Nombre      : %s%n", nombre);
                System.out.printf("  -> Precio      : S/. %.2f%n", precio);
                System.out.printf("  -> Stock       : %d uds.%n", stock);

            } catch (NumberFormatException e) {
                System.out.println("[!] Precio o stock invalido. Ingrese solo numeros.");
            } catch (IllegalArgumentException e) {
                System.out.println("[!] " + e.getMessage());
            } catch (Exception e) {
                System.out.println("[ERROR] No se pudo agregar el producto: " + e.getMessage());
            }
        }

        // ── [6] Reabastecer stock ─────────────────────────────────────────

        private static void reabastecerProducto(Scanner scanner) {
            System.out.print("\n Ingrese el ID del producto a reabastecer: ");
            String idStr = scanner.nextLine().trim();

            System.out.print(" Cantidad a sumar al stock: ");
            String cantStr = scanner.nextLine().trim();

            try {
                int productoId = Integer.parseInt(idStr);
                int cantidad = Integer.parseInt(cantStr);

                InventarioService.reabastecer(productoId, cantidad);

                System.out.printf("%n  [OK] Stock actualizado. Se sumaron %d unidades al producto #%d.%n",
                        cantidad, productoId);

            } catch (NumberFormatException e) {
                System.out.println("[!] ID o cantidad invalida. Ingrese solo numeros.");
            } catch (IllegalArgumentException e) {
                System.out.println("[!] " + e.getMessage());
            } catch (Exception e) {
                System.out.println("[ERROR] " + e.getMessage());
            }
        }

        // ── [7] Ultimas ventas procesadas ─────────────────────────────────

        private static void mostrarUltimasVentas() {
            System.out.println("\n======================================================================");
            System.out.println("  ULTIMAS VENTAS PROCESADAS  (en memoria — maximo 50 entradas)      ");
            System.out.println("======================================================================");

            if (logVentas.isEmpty()) {
                System.out.println("  [!] Sin movimientos registrados desde que arranco el servidor.");
            } else {
                for (String linea : logVentas) {
                    System.out.println(linea);
                }
            }
            System.out.println("======================================================================");
        }

        // ── Limpiar pantalla ──────────────────────────────────────────────

        private static void limpiarPantalla() {
            // En Linux/Debian usa el código ANSI; en Windows imprime líneas en blanco
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("nix") || os.contains("nux") || os.contains("mac")) {
                System.out.print("\033[H\033[2J");
                System.out.flush();
            } else {
                for (int i = 0; i < 50; i++)
                    System.out.println();
            }
        }

        // ── Helper: imprimir tabla de stock ──────────────────────────────

        private static void imprimirTablaStock(String titulo, List<FilaStock> lista) {
            System.out.println("\n----------------------------------------------------------------------");
            System.out.printf("  %s%n", titulo);
            System.out.println("----------------------------------------------------------------------");
            System.out.printf(" %-6s | %-14s | %-24s | %-10s | %-6s | %s%n",
                    "ID", "Codigo", "Nombre", "Precio", "Stock", "Estado");
            System.out.println(" --------------------------------------------------------------------");

            if (lista.isEmpty()) {
                System.out.println("  [!] Sin registros.");
            } else {
                for (FilaStock f : lista) {
                    System.out.printf(" %-6d | %-14s | %-24s | S/. %-6.2f | %-6d | %s%n",
                            f.id,
                            f.codigoBarras,
                            f.nombre.length() > 24 ? f.nombre.substring(0, 23) + "…" : f.nombre,
                            f.precio,
                            f.stock,
                            f.alerta.isEmpty() ? "OK" : "! " + f.alerta);
                }
            }
            System.out.println("----------------------------------------------------------------------");
        }
    }
}