package com.autoservicio.ventas;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

// ═══════════════════════════════════════════════════════════════════════════
//  MS-VENTAS  —  Refactorizado (todo en un solo archivo)
// ═══════════════════════════════════════════════════════════════════════════

public class Ventas {

    public static void main(String[] args) {
        ConnectionPool.init();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[INFO] Apagando servidor...");
            ConnectionPool.shutdown();
        }));

        Thread menuThread = new Thread(SupervisorMenu::iniciar);
        menuThread.setDaemon(true);
        menuThread.start();

        VentasServer.iniciar();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MODELO
    // ═══════════════════════════════════════════════════════════════════════

    static class DetalleBoleta {
        private final int productoId;
        private final int cantidad;
        private final double precioUnitario;
        private final double subtotal;

        DetalleBoleta(int productoId, int cantidad, double precioUnitario) {
            if (productoId <= 0)
                throw new IllegalArgumentException("productoId inválido: " + productoId);
            if (cantidad <= 0)
                throw new IllegalArgumentException("cantidad inválida: " + cantidad);
            if (precioUnitario < 0)
                throw new IllegalArgumentException("precio negativo: " + precioUnitario);
            this.productoId = productoId;
            this.cantidad = cantidad;
            this.precioUnitario = precioUnitario;
            this.subtotal = precioUnitario * cantidad;
        }

        int getProductoId() {
            return productoId;
        }

        int getCantidad() {
            return cantidad;
        }

        double getPrecioUnitario() {
            return precioUnitario;
        }

        double getSubtotal() {
            return subtotal;
        }
    }

    static class Boleta {
        private final String cajaId;
        private final double total;
        private final List<DetalleBoleta> detalles;

        Boleta(String cajaId, double total, List<DetalleBoleta> detalles) {
            this.cajaId = cajaId;
            this.total = total;
            this.detalles = detalles;
        }

        String getCajaId() {
            return cajaId;
        }

        double getTotal() {
            return total;
        }

        List<DetalleBoleta> getDetalles() {
            return detalles;
        }
    }

    static class MetricasTurno {
        final int totalBoletas;
        final double totalDinero;
        final int boletaBaseCorte;

        MetricasTurno(int totalBoletas, double totalDinero, int boletaBaseCorte) {
            this.totalBoletas = totalBoletas;
            this.totalDinero = totalDinero;
            this.boletaBaseCorte = boletaBaseCorte;
        }
    }

    static class FilaHistorial {
        final int id;
        final String cajaId;
        final double total;
        final String fechaHora;
        final boolean esTurnoActual;

        FilaHistorial(int id, String cajaId, double total, String fechaHora, boolean esTurnoActual) {
            this.id = id;
            this.cajaId = cajaId;
            this.total = total;
            this.fechaHora = fechaHora;
            this.esTurnoActual = esTurnoActual;
        }
    }

    static class FilaDetalle {
        final int productoId;
        final int cantidad;
        final double precioUnitario;
        final double subtotal;

        FilaDetalle(int productoId, int cantidad, double precioUnitario, double subtotal) {
            this.productoId = productoId;
            this.cantidad = cantidad;
            this.precioUnitario = precioUnitario;
            this.subtotal = subtotal;
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
                throw new IllegalStateException("Pool no inicializado. Llama a ConnectionPool.init() primero.");
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

    static final class VentasRepository {

        private VentasRepository() {
        }

        static int grabarBoleta(Boleta boleta) throws Exception {
            final String sqlCab = "INSERT INTO ventas_cabecera (caja_id, total) VALUES (?, ?)";
            final String sqlDet = "INSERT INTO ventas_detalle "
                    + "(venta_id, producto_id, cantidad, precio_unitario, subtotal) "
                    + "VALUES (?, ?, ?, ?, ?)";

            try (Connection con = ConnectionPool.obtenerConexion()) {
                con.setAutoCommit(false);
                try {
                    int ventaId;
                    try (PreparedStatement psCab = con.prepareStatement(sqlCab, Statement.RETURN_GENERATED_KEYS)) {
                        psCab.setString(1, boleta.getCajaId());
                        psCab.setDouble(2, boleta.getTotal());
                        psCab.executeUpdate();
                        try (ResultSet keys = psCab.getGeneratedKeys()) {
                            if (!keys.next())
                                throw new Exception("No se generó ID para la boleta.");
                            ventaId = keys.getInt(1);
                        }
                    }

                    try (PreparedStatement psDet = con.prepareStatement(sqlDet)) {
                        for (DetalleBoleta d : boleta.getDetalles()) {
                            psDet.setInt(1, ventaId);
                            psDet.setInt(2, d.getProductoId());
                            psDet.setInt(3, d.getCantidad());
                            psDet.setDouble(4, d.getPrecioUnitario());
                            psDet.setDouble(5, d.getSubtotal());
                            psDet.addBatch();
                        }
                        psDet.executeBatch();
                    }

                    con.commit();
                    return ventaId;

                } catch (Exception e) {
                    con.rollback();
                    throw e;
                }
            }
        }

        static MetricasTurno obtenerMetricasTurno(int baseCorte) throws Exception {
            final String sql = "SELECT COUNT(*) AS total_boletas, IFNULL(SUM(total), 0) AS total_dinero "
                    + "FROM ventas_cabecera WHERE id > ?";
            try (Connection con = ConnectionPool.obtenerConexion();
                    PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, baseCorte);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new MetricasTurno(
                                rs.getInt("total_boletas"),
                                rs.getDouble("total_dinero"),
                                baseCorte);
                    }
                }
            }
            return new MetricasTurno(0, 0.0, baseCorte);
        }

        static List<FilaHistorial> obtenerHistorial(int baseCorte) throws Exception {
            final String sql = "SELECT id, caja_id, total, fecha_hora FROM ventas_cabecera ORDER BY id ASC";
            List<FilaHistorial> resultado = new ArrayList<>();
            try (Connection con = ConnectionPool.obtenerConexion();
                    PreparedStatement ps = con.prepareStatement(sql);
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    resultado.add(new FilaHistorial(
                            id,
                            rs.getString("caja_id"),
                            rs.getDouble("total"),
                            rs.getString("fecha_hora"),
                            id > baseCorte));
                }
            }
            return resultado;
        }

        static List<FilaDetalle> obtenerDetalleBoleta(int boletaId) throws Exception {
            final String sqlCab = "SELECT id FROM ventas_cabecera WHERE id = ?";
            try (Connection con = ConnectionPool.obtenerConexion();
                    PreparedStatement psCab = con.prepareStatement(sqlCab)) {
                psCab.setInt(1, boletaId);
                try (ResultSet rs = psCab.executeQuery()) {
                    if (!rs.next())
                        return null;
                }
            }

            final String sql = "SELECT producto_id, cantidad, precio_unitario, subtotal "
                    + "FROM ventas_detalle WHERE venta_id = ? ORDER BY producto_id ASC";
            List<FilaDetalle> resultado = new ArrayList<>();
            try (Connection con = ConnectionPool.obtenerConexion();
                    PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, boletaId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        resultado.add(new FilaDetalle(
                                rs.getInt("producto_id"),
                                rs.getInt("cantidad"),
                                rs.getDouble("precio_unitario"),
                                rs.getDouble("subtotal")));
                    }
                }
            }
            return resultado;
        }

        static int obtenerMaxId() throws Exception {
            final String sql = "SELECT IFNULL(MAX(id), 0) AS max_id FROM ventas_cabecera";
            try (Connection con = ConnectionPool.obtenerConexion();
                    PreparedStatement ps = con.prepareStatement(sql);
                    ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return rs.getInt("max_id");
            }
            return 0;
        }

        static void persistirCorte(int boletaBaseId) throws Exception {
            final String sql = "INSERT INTO turnos_corte (boleta_base_id) VALUES (?)";
            try (Connection con = ConnectionPool.obtenerConexion();
                    PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, boletaBaseId);
                ps.executeUpdate();
            }
        }

        static int cargarUltimoCorte() throws Exception {
            final String sql = "SELECT boleta_base_id FROM turnos_corte ORDER BY id DESC LIMIT 1";
            try (Connection con = ConnectionPool.obtenerConexion();
                    PreparedStatement ps = con.prepareStatement(sql);
                    ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return rs.getInt("boleta_base_id");
            }
            return 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SERVICE — Lógica de negocio y validación
    // ═══════════════════════════════════════════════════════════════════════

    static final class VentasService {

        private static final Logger LOG = Logger.getLogger("VentasService");
        private static final AtomicInteger idBoletaBaseCorte = new AtomicInteger(0);

        private VentasService() {
        }

        static void cargarCorteInicial() {
            try {
                int corte = VentasRepository.cargarUltimoCorte();
                idBoletaBaseCorte.set(corte);
                LOG.info("[SERVICIO] Corte de turno cargado desde BD: #" + corte);
            } catch (Exception e) {
                LOG.warning("[SERVICIO] No se pudo cargar el corte de turno: " + e.getMessage()
                        + " — Se asume corte = 0.");
            }
        }

        static JsonObject registrarBoleta(String jsonRaw) {
            JsonObject respuesta = new JsonObject();
            try {
                JsonObject req = JsonParser.parseString(jsonRaw).getAsJsonObject();

                validarCampoRequerido(req, "caja_id");
                validarCampoRequerido(req, "total");
                validarCampoRequerido(req, "detalles");

                String cajaId = req.get("caja_id").getAsString().trim();
                double total = req.get("total").getAsDouble();
                JsonArray detArr = req.getAsJsonArray("detalles");

                if (cajaId.isEmpty())
                    throw new IllegalArgumentException("caja_id no puede estar vacío.");
                if (total <= 0)
                    throw new IllegalArgumentException("El total debe ser mayor a 0. Recibido: " + total);
                if (detArr == null || detArr.size() == 0)
                    throw new IllegalArgumentException("La boleta debe tener al menos un producto.");

                List<DetalleBoleta> detalles = new ArrayList<>();
                for (JsonElement elem : detArr) {
                    JsonObject item = elem.getAsJsonObject();
                    int productoId = item.get("id").getAsInt();
                    int cantidad = item.has("cantidad")
                            ? item.get("cantidad").getAsInt()
                            : item.get("amount").getAsInt();
                    double precio = item.get("precio").getAsDouble();
                    detalles.add(new DetalleBoleta(productoId, cantidad, precio));
                }

                Boleta boleta = new Boleta(cajaId, total, detalles);
                int ventaId = VentasRepository.grabarBoleta(boleta);

                respuesta.addProperty("status", "SUCCESS");
                respuesta.addProperty("boleta_id", ventaId);

            } catch (IllegalArgumentException e) {
                respuesta.addProperty("status", "INVALID");
                respuesta.addProperty("mensaje", e.getMessage());
                LOG.warning("[SERVICIO] Validación fallida: " + e.getMessage());
            } catch (Exception e) {
                respuesta.addProperty("status", "FAILED");
                respuesta.addProperty("mensaje", "Error interno: " + e.getMessage());
                LOG.severe("[SERVICIO] Error al grabar boleta: " + e.getMessage());
            }
            return respuesta;
        }

        static MetricasTurno obtenerMetricasTurno() throws Exception {
            return VentasRepository.obtenerMetricasTurno(idBoletaBaseCorte.get());
        }

        static List<FilaHistorial> obtenerHistorial() throws Exception {
            return VentasRepository.obtenerHistorial(idBoletaBaseCorte.get());
        }

        static int reiniciarTurno() throws Exception {
            int maxId = VentasRepository.obtenerMaxId();
            VentasRepository.persistirCorte(maxId);
            idBoletaBaseCorte.set(maxId);
            LOG.info("[SERVICIO] Turno reiniciado. Nuevo corte: #" + maxId);
            return maxId;
        }

        static int getBaseCorte() {
            return idBoletaBaseCorte.get();
        }

        static JsonObject generarReporteGerencial() {
            JsonObject reporte = new JsonObject();
            try {
                MetricasTurno metricas = VentasRepository.obtenerMetricasTurno(idBoletaBaseCorte.get());
                reporte.addProperty("status", "OK");
                reporte.addProperty("total_boletas", metricas.totalBoletas);
                reporte.addProperty("total_dinero", metricas.totalDinero);
                reporte.addProperty("boleta_corte", metricas.boletaBaseCorte);

                List<FilaHistorial> historial = VentasRepository.obtenerHistorial(idBoletaBaseCorte.get());
                JsonArray boletas = new JsonArray();
                for (FilaHistorial f : historial) {
                    JsonObject b = new JsonObject();
                    b.addProperty("id", f.id);
                    b.addProperty("caja_id", f.cajaId);
                    b.addProperty("total", f.total);
                    b.addProperty("fecha_hora", f.fechaHora);
                    b.addProperty("es_turno_actual", f.esTurnoActual);
                    boletas.add(b);
                }
                reporte.add("boletas", boletas);

            } catch (Exception e) {
                reporte.addProperty("status", "ERROR");
                reporte.addProperty("mensaje", "Error al generar reporte: " + e.getMessage());
                LOG.severe("[SERVICIO] Error en reporte gerencial: " + e.getMessage());
            }
            return reporte;
        }

        static List<FilaDetalle> obtenerDetalleBoleta(int boletaId) throws Exception {
            return VentasRepository.obtenerDetalleBoleta(boletaId);
        }

        private static void validarCampoRequerido(JsonObject obj, String campo) {
            if (!obj.has(campo) || obj.get(campo).isJsonNull()) {
                throw new IllegalArgumentException("Campo requerido ausente: '" + campo + "'.");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SERVER — Acepta conexiones TCP y despacha hilos
    // ═══════════════════════════════════════════════════════════════════════

    static final class VentasServer {

        private static final int PUERTO = 8082;
        private static final Logger LOG = Logger.getLogger("VentasServer");

        private VentasServer() {
        }

        static void iniciar() {
            VentasService.cargarCorteInicial();
            System.out.println("=== MS-VENTAS ACTIVO [Puerto " + PUERTO + "] ===");

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

                JsonObject parsed = JsonParser.parseString(jsonRaw).getAsJsonObject();
                String accion = parsed.has("accion") ? parsed.get("accion").getAsString() : "GRABAR_VENTA";

                JsonObject respuesta;
                if ("REPORTE_GERENCIAL".equals(accion)) {
                    respuesta = VentasService.generarReporteGerencial();
                } else {
                    respuesta = VentasService.registrarBoleta(jsonRaw);
                }

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

        static void iniciar() {
            Scanner scanner = new Scanner(System.in);
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }

            while (true) {
                // ── Limpiar pantalla antes de redibujar el menú ──
                limpiarPantalla();

                System.out.println("==================================================");
                System.out.println("      MENU DE SUPERVISOR - AUTOSERVICIO GREEN     ");
                System.out.println("==================================================");
                System.out.println(" [1] Ver Recaudacion del Turno Actual");
                System.out.println(" [2] Ver Historial Completo de Ventas");
                System.out.println(" [3] Reiniciar Turno / Iniciar Nuevo Dia");
                System.out.println(" [4] Ver Detalle de una Boleta");
                System.out.println("--------------------------------------------------");
                System.out.print(" Ingrese una opcion: ");

                String opcion = scanner.nextLine().trim();

                switch (opcion) {
                    case "1":
                        mostrarMetricasTurno();
                        break;
                    case "2":
                        mostrarHistorial();
                        break;
                    case "3":
                        reiniciarTurno(scanner);
                        break;
                    case "4":
                        mostrarDetalleBoleta(scanner);
                        break;
                    default:
                        System.out.println("[!] Opcion invalida.");
                        break;
                }

                // ── Pausa para que el supervisor pueda leer el resultado ──
                System.out.println("\n Presione ENTER para volver al menu...");
                scanner.nextLine();
            }
        }

        // ── [1] Métricas del turno ────────────────────────────────────────

        private static void mostrarMetricasTurno() {
            try {
                MetricasTurno m = VentasService.obtenerMetricasTurno();
                System.out.println("\n--------------------------------------------------");
                System.out.println("     METRICAS DEL TURNO ACTUAL (MySQL LIVE)       ");
                System.out.println("--------------------------------------------------");
                System.out.printf(" -> Boletas en este turno : %d%n", m.totalBoletas);
                System.out.printf(" -> Dinero recaudado hoy  : S/. %.2f%n", m.totalDinero);
                if (m.boletaBaseCorte > 0) {
                    System.out.printf(" -> Corte de control base : Boleta #%d%n", m.boletaBaseCorte);
                }
                System.out.println("--------------------------------------------------");
            } catch (Exception e) {
                System.out.println("[ERROR] No se pudieron obtener metricas: " + e.getMessage());
            }
        }

        // ── [2] Historial completo ────────────────────────────────────────

        private static void mostrarHistorial() {
            try {
                List<FilaHistorial> historial = VentasService.obtenerHistorial();
                System.out.println("\n----------------------------------------------------------------------");
                System.out.println("          AUDITORIA HISTORICA COMPLETA DE VENTAS                      ");
                System.out.println("----------------------------------------------------------------------");
                System.out.printf(" %-10s | %-22s | %-14s | %s%n",
                        "Nro Boleta", "ID Terminal POS", "Monto Total", "Turno");
                System.out.println(" --------------------------------------------------------------------");

                if (historial.isEmpty()) {
                    System.out.println("  [!] No existen registros en la base de datos central.");
                } else {
                    for (FilaHistorial f : historial) {
                        System.out.printf("  # %-8d | %-22s | S/. %-9.2f | %s%n",
                                f.id, f.cajaId, f.total,
                                f.esTurnoActual ? "[TURNO ACTUAL]" : "[HISTORICO]");
                    }
                }
                System.out.println("----------------------------------------------------------------------");
            } catch (Exception e) {
                System.out.println("[ERROR] No se pudo cargar el historial: " + e.getMessage());
            }
        }

        // ── [3] Reiniciar turno ───────────────────────────────────────────

        private static void reiniciarTurno(Scanner scanner) {
            System.out.print("\n[!] Confirma el cierre de caja y reinicio del dia? (s/n): ");
            String confirm = scanner.nextLine().trim();

            if ("s".equalsIgnoreCase(confirm) || "si".equalsIgnoreCase(confirm)) {
                try {
                    int nuevoCorte = VentasService.reiniciarTurno();
                    System.out.println("\n==================================================");
                    System.out.println("  CIERRE DE TURNO EXITOSO. CONTROL REINICIADO     ");
                    System.out.println("==================================================");
                    System.out.println(" -> Contador de ventas vuelto a 0.");
                    System.out.println(" -> Total acumulado vuelto a S/. 0.00.");
                    System.out.printf(" -> El corte queda guardado en BD (boleta #%d).%n", nuevoCorte);
                    System.out.printf(" -> Proxima boleta desde ID: #%d%n", nuevoCorte + 1);
                    System.out.println("==================================================");
                } catch (Exception e) {
                    System.out.println("[ERROR] No se pudo reiniciar el turno: " + e.getMessage());
                }
            } else {
                System.out.println("[INFO] Operacion cancelada.");
            }
        }

        // ── [4] Detalle de boleta ─────────────────────────────────────────

        private static void mostrarDetalleBoleta(Scanner scanner) {
            System.out.print("\n Ingrese el numero de boleta a consultar: ");
            String input = scanner.nextLine().trim();

            int boletaId;
            try {
                boletaId = Integer.parseInt(input);
            } catch (NumberFormatException e) {
                System.out.println("[!] Numero de boleta invalido.");
                return;
            }

            try {
                List<FilaDetalle> detalles = VentasService.obtenerDetalleBoleta(boletaId);

                if (detalles == null) {
                    System.out.println("\n  [!] No existe la boleta #" + boletaId + ".");
                    return;
                }

                System.out.println("\n----------------------------------------------------------------------");
                System.out.printf("          DETALLE DE BOLETA #%-5d%n", boletaId);
                System.out.println("----------------------------------------------------------------------");
                System.out.printf(" %-12s | %-10s | %-14s | %s%n",
                        "Producto ID", "Cantidad", "Precio Unit.", "Subtotal");
                System.out.println(" --------------------------------------------------------------------");

                if (detalles.isEmpty()) {
                    System.out.println("  [!] La boleta no tiene productos registrados.");
                } else {
                    double totalCalculado = 0;
                    for (FilaDetalle d : detalles) {
                        System.out.printf("  %-12d | %-10d | S/. %-10.2f | S/. %.2f%n",
                                d.productoId, d.cantidad, d.precioUnitario, d.subtotal);
                        totalCalculado += d.subtotal;
                    }
                    System.out.println(" --------------------------------------------------------------------");
                    System.out.printf("  %-25s              TOTAL: S/. %.2f%n", "", totalCalculado);
                }
                System.out.println("----------------------------------------------------------------------");

            } catch (Exception e) {
                System.out.println("[ERROR] No se pudo obtener el detalle: " + e.getMessage());
            }
        }

        // ── Limpiar pantalla ──────────────────────────────────────────────

        private static void limpiarPantalla() {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("nix") || os.contains("nux") || os.contains("mac")) {
                System.out.print("\033[H\033[2J");
                System.out.flush();
            } else {
                for (int i = 0; i < 50; i++)
                    System.out.println();
            }
        }
    }
}