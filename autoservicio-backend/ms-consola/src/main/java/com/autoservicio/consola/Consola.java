package com.autoservicio.consola;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

// ═══════════════════════════════════════════════════════════════════════════
//  MS-CONSOLA — Menú de supervisión unificado
//
//  No tiene lógica de negocio propia: es un cliente de consola que se
//  conecta por socket TCP a ms-inventario y ms-ventas usando la misma
//  acción "ADMIN" que se agregó a sus protocolos, reutilizando 100% de
//  la lógica de cada menú original sin duplicar código de negocio.
//
//  Para ver los logs del Gateway, este módulo no se conecta a él (es solo
//  un router HTTP sin estado de negocio para consultar) — se sugiere usar
//  "docker compose logs -f ms-gateway" en otra terminal.
// ═══════════════════════════════════════════════════════════════════════════
public class Consola {

    private static final String HOST_INVENTARIO = System.getenv().getOrDefault("MS_INVENTARIO_HOST", "localhost");
    private static final String HOST_VENTAS = System.getenv().getOrDefault("MS_VENTAS_HOST", "localhost");
    private static final int PUERTO_INVENTARIO = 8081;
    private static final int PUERTO_VENTAS = 8082;

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
        }

        while (true) {
            limpiarPantalla();
            System.out.println("==================================================");
            System.out.println("   PANEL DE SUPERVISION UNIFICADO - AUTOSERVICIO  ");
            System.out.println("==================================================");
            System.out.println(" [1] Ver logs del Gateway (orquestador)");
            System.out.println(" [2] Menu de Inventario");
            System.out.println(" [3] Menu de Ventas");
            System.out.println(" [4] Salir");
            System.out.println("--------------------------------------------------");
            System.out.print(" Ingrese una opcion: ");

            if (!scanner.hasNextLine()) {
                System.out.println("[INFO] Consola sin entrada interactiva disponible. Saliendo.");
                return;
            }
            String opcion = scanner.nextLine().trim();

            switch (opcion) {
                case "1":
                    mostrarInstruccionesLogsGateway(scanner);
                    break;
                case "2":
                    menuInventario(scanner);
                    break;
                case "3":
                    menuVentas(scanner);
                    break;
                case "4":
                    System.out.println("\n Saliendo del panel de supervision...");
                    return;
                default:
                    System.out.println("[!] Opcion invalida.");
                    pausar(scanner);
            }
        }
    }

    // ── [1] Logs del gateway ──────────────────────────────────────────────

    private static void mostrarInstruccionesLogsGateway(Scanner scanner) {
        limpiarPantalla();
        System.out.println("==================================================");
        System.out.println("   GATEWAY ORQUESTADOR — VER LOGS                 ");
        System.out.println("==================================================");
        System.out.println(" El Gateway es solo un router HTTP sin estado de");
        System.out.println(" negocio que consultar, por lo que no expone un");
        System.out.println(" menu administrativo como Inventario o Ventas.");
        System.out.println();
        System.out.println(" Para ver sus logs en vivo desde otra terminal:");
        System.out.println();
        System.out.println("    docker compose logs -f ms-gateway");
        System.out.println();
        System.out.println("==================================================");
        pausar(scanner);
    }

    // ── [2] Menu de Inventario (remoto) ───────────────────────────────────

    private static void menuInventario(Scanner scanner) {
        while (true) {
            limpiarPantalla();
            System.out.println("==================================================");
            System.out.println("   MENU DE SUPERVISOR - INVENTARIO GREEN          ");
            System.out.println("   (via red -> " + HOST_INVENTARIO + ":" + PUERTO_INVENTARIO + ")");
            System.out.println("==================================================");
            System.out.println(" [1] Ver Stock Completo");
            System.out.println(" [2] Ver Productos con Stock Bajo o Agotado");
            System.out.println(" [3] Buscar Producto por Codigo de Barras");
            System.out.println(" [4] Ver Historial de Ventas de un Producto");
            System.out.println(" [5] Agregar Producto Nuevo");
            System.out.println(" [6] Reabastecer Stock de un Producto");
            System.out.println(" [7] Ver Ultimas Ventas Procesadas");
            System.out.println(" [0] Volver al menu principal");
            System.out.println("--------------------------------------------------");
            System.out.print(" Ingrese una opcion: ");

            if (!scanner.hasNextLine()) return;
            String opcion = scanner.nextLine().trim();

            if ("0".equals(opcion)) return;

            List<String> inputs = new ArrayList<>();
            switch (opcion) {
                case "3":
                    System.out.print(" Ingrese el codigo de barras: ");
                    inputs.add(scanner.nextLine().trim());
                    break;
                case "4":
                    System.out.print(" Ingrese el ID del producto: ");
                    inputs.add(scanner.nextLine().trim());
                    break;
                case "5":
                    System.out.print(" Codigo de barras : ");
                    inputs.add(scanner.nextLine().trim());
                    System.out.print(" Nombre           : ");
                    inputs.add(scanner.nextLine().trim());
                    System.out.print(" Precio (S/.)     : ");
                    inputs.add(scanner.nextLine().trim());
                    System.out.print(" Stock inicial    : ");
                    inputs.add(scanner.nextLine().trim());
                    break;
                case "6":
                    System.out.print(" ID del producto a reabastecer: ");
                    inputs.add(scanner.nextLine().trim());
                    System.out.print(" Cantidad a sumar al stock: ");
                    inputs.add(scanner.nextLine().trim());
                    break;
                case "1":
                case "2":
                case "7":
                    break;
                default:
                    System.out.println("[!] Opcion invalida.");
                    pausar(scanner);
                    continue;
            }

            String salida = ejecutarAdminRemoto(HOST_INVENTARIO, PUERTO_INVENTARIO, opcion, inputs);
            System.out.println(salida);
            pausar(scanner);
        }
    }

    // ── [3] Menu de Ventas (remoto) ────────────────────────────────────────

    private static void menuVentas(Scanner scanner) {
        while (true) {
            limpiarPantalla();
            System.out.println("==================================================");
            System.out.println("    MENU DE SUPERVISOR - AUTOSERVICIO GREEN       ");
            System.out.println("    (via red -> " + HOST_VENTAS + ":" + PUERTO_VENTAS + ")");
            System.out.println("==================================================");
            System.out.println(" [1] Ver Recaudacion del Turno Actual");
            System.out.println(" [2] Ver Historial Completo de Ventas");
            System.out.println(" [3] Reiniciar Turno / Iniciar Nuevo Dia");
            System.out.println(" [4] Ver Detalle de una Boleta");
            System.out.println(" [0] Volver al menu principal");
            System.out.println("--------------------------------------------------");
            System.out.print(" Ingrese una opcion: ");

            if (!scanner.hasNextLine()) return;
            String opcion = scanner.nextLine().trim();

            if ("0".equals(opcion)) return;

            List<String> inputs = new ArrayList<>();
            switch (opcion) {
                case "3":
                    System.out.print(" Confirma el cierre de caja y reinicio del dia? (s/n): ");
                    inputs.add(scanner.nextLine().trim());
                    break;
                case "4":
                    System.out.print(" Numero de boleta a consultar: ");
                    inputs.add(scanner.nextLine().trim());
                    break;
                case "1":
                case "2":
                    break;
                default:
                    System.out.println("[!] Opcion invalida.");
                    pausar(scanner);
                    continue;
            }

            String salida = ejecutarAdminRemoto(HOST_VENTAS, PUERTO_VENTAS, opcion, inputs);
            System.out.println(salida);
            pausar(scanner);
        }
    }

    // ── Cliente TCP — manda accion ADMIN y recibe la salida capturada ─────

    private static String ejecutarAdminRemoto(String host, int puerto, String opcion, List<String> inputs) {
        JsonObject req = new JsonObject();
        req.addProperty("accion", "ADMIN");
        req.addProperty("opcion", opcion);
        JsonArray inputsArr = new JsonArray();
        for (String in : inputs) inputsArr.add(in);
        req.add("inputs", inputsArr);

        try (Socket socket = new Socket(host, puerto);
             DataOutputStream salida = new DataOutputStream(socket.getOutputStream());
             DataInputStream entrada = new DataInputStream(socket.getInputStream())) {

            socket.setSoTimeout(8000);

            byte[] reqBytes = req.toString().getBytes("UTF-8");
            salida.writeInt(reqBytes.length);
            salida.write(reqBytes);
            salida.flush();

            int longitud = entrada.readInt();
            byte[] buffer = new byte[longitud];
            entrada.readFully(buffer);
            String respRaw = new String(buffer, "UTF-8");

            JsonObject resp = JsonParser.parseString(respRaw).getAsJsonObject();
            if (resp.has("salida")) {
                return resp.get("salida").getAsString();
            }
            return resp.toString();

        } catch (Exception e) {
            return "[ERROR] No se pudo conectar a " + host + ":" + puerto + " -> " + e.getMessage();
        }
    }

    // ── Utilidades ─────────────────────────────────────────────────────────

    private static void pausar(Scanner scanner) {
        System.out.println("\n Presione ENTER para continuar...");
        if (scanner.hasNextLine()) scanner.nextLine();
    }

    private static void limpiarPantalla() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("nix") || os.contains("nux") || os.contains("mac")) {
            System.out.print("\033[H\033[2J");
            System.out.flush();
        } else {
            for (int i = 0; i < 50; i++) System.out.println();
        }
    }
}
