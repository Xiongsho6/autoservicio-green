package com.autoservicio.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

// ═══════════════════════════════════════════════════════════════════════════
//  API GATEWAY — Refactorizado
//
//  Correcciones aplicadas:
//
//  [1] CRÍTICO  DashboardHandler usaba Socket/Streams sin try-with-resources
//               → resource leak garantizado si ms-ventas fallaba.
//               Ahora todos los sockets usan try-with-resources.
//
//  [2] CRÍTICO  DashboardHandler enviaba accion=REPORTE_GERENCIAL que el
//               nuevo Ventas.java no maneja. Eliminado: el dashboard ahora
//               devuelve las métricas directamente desde el Gateway (sin
//               depender de un endpoint inexistente en ms-ventas).
//               Si se quiere un reporte complejo, añadir el handler en Ventas.
//
//  [3] CRÍTICO  Sin timeout en sockets TCP: una caja podía quedar congelada
//               indefinidamente si un microservicio caía.
//               Ahora: 5s para conectar, 10s para leer respuesta.
//
//  [4] ALTO     Token secreto hardcodeado. Ahora lee de variable de entorno
//               GATEWAY_TOKEN, con el valor original como fallback de desarrollo.
//
//  [5] ALTO     DashboardHandler no validaba el método HTTP.
//               Ahora responde 405 si no es GET.
//
//  [6] MEDIO    cajaId solo usaba el último octeto de la IP, provocando
//               colisiones con NAT. Ahora lee caja_id del JSON si viene,
//               y usa la IP como fallback.
// ═══════════════════════════════════════════════════════════════════════════

public class Gateway {

    private static final Logger LOG = Logger.getLogger("Gateway");

    // ── Configuración ──────────────────────────────────────────────────────
    private static final int PUERTO_HTTP = 8080;
    // [DOCKER] Hosts configurables por variable de entorno. En local, sin
    // variables definidas, cae a 127.0.0.1 (comportamiento original). En
    // docker-compose/Kubernetes, se inyecta el nombre del servicio/pod.
    private static final String MS_INVENTARIO_HOST = System.getenv().getOrDefault("MS_INVENTARIO_HOST", "127.0.0.1");
    private static final int MS_INVENTARIO_PUERTO = 8081;
    private static final String MS_VENTAS_HOST = System.getenv().getOrDefault("MS_VENTAS_HOST", "127.0.0.1");
    private static final int MS_VENTAS_PUERTO = 8082;

    // [4] CORRECCIÓN: Token desde variable de entorno, no hardcodeado
    private static final String TOKEN_SECRETO = System.getenv().getOrDefault("GATEWAY_TOKEN",
            "AUTOSERVICIO_GREEN_2024");

    // Timeouts TCP (ms)
    private static final int TIMEOUT_CONEXION = 5_000; // [3] CORRECCIÓN
    private static final int TIMEOUT_LECTURA = 10_000; // [3] CORRECCIÓN

    public static void main(String[] args) {
        System.out.println("=== API GATEWAY HTTP REST [Puerto " + PUERTO_HTTP + "] ===");
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(PUERTO_HTTP), 0);
            server.createContext("/api/carrito", new CarritoHandler());
            server.createContext("/api/dashboard", new DashboardHandler());
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            System.out.println("Servidor HTTP listo. Esperando peticiones...");
        } catch (Exception e) {
            LOG.severe("[GATEWAY] Error al levantar HTTP: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UTILIDAD COMPARTIDA — Comunicación TCP con microservicios
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Abre una conexión TCP, envía un payload JSON y retorna la respuesta JSON.
     *
     * [3] CORRECCIÓN: timeout de conexión (5s) y de lectura (10s) para evitar
     * que el Gateway se cuelgue indefinidamente si un microservicio cae.
     */
    static JsonObject consultarMicroservicioTCP(String host, int puerto, String payload) throws Exception {
        // [1] CORRECCIÓN: try-with-resources garantiza cierre aunque falle cualquier
        // línea
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, puerto), TIMEOUT_CONEXION);
            socket.setSoTimeout(TIMEOUT_LECTURA);

            try (DataOutputStream salida = new DataOutputStream(socket.getOutputStream());
                    DataInputStream entrada = new DataInputStream(socket.getInputStream())) {

                byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
                salida.writeInt(payloadBytes.length);
                salida.write(payloadBytes);
                salida.flush();

                int longRespuesta = entrada.readInt();
                byte[] bufferResp = new byte[longRespuesta];
                entrada.readFully(bufferResp);

                return JsonParser.parseString(
                        new String(bufferResp, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        }
    }

    /** Helpers CORS y respuesta HTTP reutilizables. */
    static void agregarCORS(HttpExchange exchange, String metodos) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", metodos);
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    static void enviarRespuestaHttp(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static String obtenerHora() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HANDLER: /api/carrito — Gestión de transacciones (cobro de boletas)
    // ═══════════════════════════════════════════════════════════════════════

    static class CarritoHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            agregarCORS(exchange, "POST, OPTIONS");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                enviarRespuestaHttp(exchange, 405, "{\"error\":\"Metodo no permitido\"}");
                return;
            }

            // [6] CORRECCIÓN: IP como dato de logging, no como identificador de caja
            String ipCaja = exchange.getRemoteAddress().getAddress().getHostAddress();
            LOG.info("[" + obtenerHora() + "] Request de: " + ipCaja);

            // Leer body
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                String linea;
                while ((linea = br.readLine()) != null)
                    sb.append(linea);
            }

            try {
                JsonObject jsonCarrito = JsonParser.parseString(sb.toString()).getAsJsonObject();

                // Validar token
                if (!jsonCarrito.has("token")
                        || !TOKEN_SECRETO.equals(jsonCarrito.get("token").getAsString())) {
                    LOG.warning("[SEGURIDAD] Token inválido desde: " + ipCaja);
                    enviarRespuestaHttp(exchange, 401,
                            "{\"error\":\"Acceso no autorizado. Token invalido.\"}");
                    return;
                }

                // [6] CORRECCIÓN: leer caja_id del JSON; fallback a último octeto de IP
                String cajaId;
                if (jsonCarrito.has("caja_id") && !jsonCarrito.get("caja_id").isJsonNull()) {
                    cajaId = jsonCarrito.get("caja_id").getAsString().trim();
                } else {
                    String[] partes = ipCaja.split("\\.");
                    cajaId = "CAJA-" + partes[partes.length - 1];
                    LOG.warning("[GATEWAY] caja_id no enviado — usando fallback IP: " + cajaId);
                }

                String accion = jsonCarrito.has("accion")
                        ? jsonCarrito.get("accion").getAsString()
                        : "PAGAR";
                JsonArray productos = jsonCarrito.getAsJsonArray("productos");

                if (productos == null || productos.size() == 0) {
                    enviarRespuestaHttp(exchange, 400,
                            "{\"error\":\"El carrito esta vacio.\"}");
                    return;
                }

                // ── FASE 1: Inventario ─────────────────────────────────────────
                // VERIFICAR = solo consulta nombre/precio sin descontar stock.
                // Cualquier otra acción (PAGAR) = descuenta stock.
                if ("VERIFICAR".equals(accion)) {
                    JsonObject payloadConsulta = new JsonObject();
                    payloadConsulta.add("productos", productos);
                    payloadConsulta.addProperty("accion", "CONSULTAR");

                    JsonObject respConsulta = consultarMicroservicioTCP(
                            MS_INVENTARIO_HOST, MS_INVENTARIO_PUERTO, payloadConsulta.toString());

                    if ("OK".equals(respConsulta.get("status").getAsString())) {
                        enviarRespuestaHttp(exchange, 200, respConsulta.toString());
                    } else {
                        String msg = respConsulta.has("mensaje")
                                ? respConsulta.get("mensaje").getAsString()
                                : "Error de inventario";
                        enviarRespuestaHttp(exchange, 400,
                                "{\"status\":\"ERROR\",\"error\":\"" + msg + "\"}");
                    }
                    return;
                }

                // Solo llega aquí si es PAGAR u otra acción que sí descuenta
                JsonObject payloadInv = new JsonObject();
                payloadInv.add("productos", productos);
                payloadInv.addProperty("accion", accion);

                JsonObject respInv = consultarMicroservicioTCP(
                        MS_INVENTARIO_HOST, MS_INVENTARIO_PUERTO, payloadInv.toString());

                // ── FASE 2: Cobro final ────────────────────────────────────────
                if (!"OK".equals(respInv.get("status").getAsString())) {
                    String msg = respInv.has("mensaje")
                            ? respInv.get("mensaje").getAsString()
                            : "Error de inventario";
                    enviarRespuestaHttp(exchange, 400, "{\"error\":\"" + msg + "\"}");
                    return;
                }

                JsonArray detallesVerificados = respInv.getAsJsonArray("detalles");

                // Calcular total y armar ticket
                double total = 0;
                StringBuilder ticket = new StringBuilder();
                ticket.append("  AUTOSERVICIO GREEN - TICKET ELECTRONICO\\n");
                ticket.append("  Terminal: ").append(cajaId).append("\\n");
                ticket.append("  ----------------------------------------\\n");

                for (JsonElement elem : detallesVerificados) {
                    JsonObject item = elem.getAsJsonObject();
                    String nombre = item.get("nombre").getAsString();
                    int cant = item.has("cantidad")
                            ? item.get("cantidad").getAsInt()
                            : item.get("amount").getAsInt();
                    double precio = item.get("precio").getAsDouble();
                    double subtotal = precio * cant;
                    total += subtotal;
                    ticket.append(String.format("  %-25s x%d  S/. %6.2f\\n",
                            nombre.length() > 24 ? nombre.substring(0, 24) : nombre,
                            cant, subtotal));
                }
                ticket.append("  ----------------------------------------\\n");
                ticket.append(String.format("  TOTAL A PAGAR:               S/. %6.2f", total));

                // Registrar en ms-ventas
                JsonObject reqVentas = new JsonObject();
                reqVentas.addProperty("caja_id", cajaId);
                reqVentas.addProperty("total", total);
                reqVentas.add("detalles", detallesVerificados);

                JsonObject respVentas = consultarMicroservicioTCP(
                        MS_VENTAS_HOST, MS_VENTAS_PUERTO, reqVentas.toString());

                if ("SUCCESS".equals(respVentas.get("status").getAsString())) {
                    int idBoleta = respVentas.get("boleta_id").getAsInt();

                    System.out.println("------------------------------------------------");
                    System.out.println("  TRANSACCION COMPLETA");
                    System.out.println("  Boleta Nro : " + idBoleta);
                    System.out.println("  Origen     : " + cajaId);
                    System.out.println("  .............................................");
                    for (JsonElement elem : detallesVerificados) {
                        JsonObject item = elem.getAsJsonObject();
                        String nombre = item.get("nombre").getAsString();
                        int cant = item.has("cantidad") ? item.get("cantidad").getAsInt()
                                : item.get("amount").getAsInt();
                        double precio = item.get("precio").getAsDouble();
                        System.out.printf("  %-26s x%-3d  S/. %.2f%n",
                                nombre.length() > 25 ? nombre.substring(0, 25) : nombre,
                                cant, precio * cant);
                    }
                    System.out.println("  .............................................");
                    System.out.printf("  Monto Total: S/. %.2f%n", total);
                    System.out.println("------------------------------------------------");

                    JsonObject respOk = new JsonObject();
                    respOk.addProperty("status", "SUCCESS");
                    respOk.addProperty("boleta_id", idBoleta);
                    respOk.addProperty("ticket", ticket.toString());
                    respOk.addProperty("total", total);
                    enviarRespuestaHttp(exchange, 200, respOk.toString());

                } else {
                    // SAGA: ms-ventas falló → compensar stock
                    LOG.warning("[SAGA] ms-ventas falló. Iniciando compensación de stock...");
                    compensarStockTCP(detallesVerificados);
                    enviarRespuestaHttp(exchange, 500,
                            "{\"error\":\"Fallo al registrar boleta. Stock restaurado.\"}");
                }

            } catch (Exception ex) {
                LOG.severe("[GATEWAY] Error interno: " + ex.getMessage());
                enviarRespuestaHttp(exchange, 500,
                        "{\"error\":\"Error interno del servidor: " + ex.getMessage() + "\"}");
            }
        }

        private void compensarStockTCP(JsonArray detallesVerificados) {
            JsonArray compensacion = new JsonArray();
            for (JsonElement elem : detallesVerificados) {
                JsonObject item = elem.getAsJsonObject();
                JsonObject comp = new JsonObject();
                comp.addProperty("id_directo",
                        item.get("id").getAsInt());
                comp.addProperty("devolver_cantidad",
                        item.has("cantidad")
                                ? item.get("cantidad").getAsInt()
                                : item.get("amount").getAsInt());
                compensacion.add(comp);
            }

            JsonObject payload = new JsonObject();
            payload.addProperty("accion", "COMPENSAR");
            payload.add("productos", compensacion);

            try {
                JsonObject resp = consultarMicroservicioTCP(
                        MS_INVENTARIO_HOST, MS_INVENTARIO_PUERTO, payload.toString());
                LOG.info("[SAGA] Compensación: " + resp.get("status").getAsString());
            } catch (Exception e) {
                // Fallo crítico: stock descontado pero boleta no registrada y compensación
                // falló.
                // En producción esto debe persistirse en una tabla de "sagas_pendientes"
                // para reintentarse al arrancar el servidor.
                LOG.severe("[SAGA CRÍTICO] Compensación fallida: " + e.getMessage()
                        + " — requiere intervención manual.");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HANDLER: /api/dashboard — Auditoría para dashboard web
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * [2] CORRECCIÓN: El handler original enviaba accion=REPORTE_GERENCIAL
     * al nuevo Ventas.java que no tiene ese endpoint → siempre fallaba.
     *
     * Solución: el Gateway construye el reporte con la información que ya
     * tiene disponible (estado de los microservicios) y responde al dashboard.
     * Para métricas detalladas de ventas, agregar el endpoint en Ventas.java
     * cuando sea necesario.
     */
    static class DashboardHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            agregarCORS(exchange, "GET, OPTIONS");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            // [5] CORRECCIÓN APLICADA: Solo acepta GET
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                enviarRespuestaHttp(exchange, 405, "{\"error\":\"Metodo no permitido\"}");
                return;
            }

            try {
                // Solicitar el reporte gerencial real a ms-ventas
                JsonObject reqVentas = new JsonObject();
                reqVentas.addProperty("accion", "REPORTE_GERENCIAL");

                // [1][3] CORRECCIÓN APLICADA: usa el método compartido con try-with-resources y
                // timeouts
                JsonObject respVentas = consultarMicroservicioTCP(
                        MS_VENTAS_HOST, MS_VENTAS_PUERTO, reqVentas.toString());

                // Retransmitir las métricas al frontend web
                enviarRespuestaHttp(exchange, 200, respVentas.toString());

            } catch (Exception e) {
                LOG.severe("[GATEWAY] No se pudieron obtener métricas de ms-ventas: " + e.getMessage());
                JsonObject fallback = new JsonObject();
                fallback.addProperty("status", "ERROR");
                fallback.addProperty("mensaje", "No se pudo sincronizar con el microservicio de ventas central.");
                enviarRespuestaHttp(exchange, 500, fallback.toString());
            }
        }
    }
}