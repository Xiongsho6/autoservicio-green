#include <iostream>
#include <string>
#include <vector>
#include <winsock2.h>
#include <ws2tcpip.h>

#pragma comment(lib, "ws2_32.lib")

#define PUERTO 8080
#define SERVIDOR_IP "127.0.0.1"
#define TOKEN_AUTH "AUTOSERVICIO_GREEN_2024"

using namespace std;

struct ItemCarrito
{
    string codigo;
    int cantidad;
};

// Obtiene la IP local de esta maquina para usarla como ID de caja
string obtenerIpLocal()
{
    char hostname[256];
    if (gethostname(hostname, sizeof(hostname)) != 0)
        return "127.0.0.1";

    struct addrinfo hints{}, *res;
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;

    if (getaddrinfo(hostname, nullptr, &hints, &res) == 0)
    {
        char ip[INET_ADDRSTRLEN];
        inet_ntop(AF_INET,
                  &((sockaddr_in *)res->ai_addr)->sin_addr,
                  ip, sizeof(ip));
        freeaddrinfo(res);
        return string(ip);
    }
    return "127.0.0.1";
}

string procesarEscapes(const string &texto)
{
    string resultado = "";
    for (size_t i = 0; i < texto.size(); i++)
    {
        if (texto[i] == '\\' && i + 1 < texto.size())
        {
            char siguiente = texto[i + 1];
            if (siguiente == 'n')
            {
                resultado += '\n';
                i++;
                continue;
            }
            else if (siguiente == 't')
            {
                resultado += '\t';
                i++;
                continue;
            }
            else if (siguiente == '\\')
            {
                // Doble barra \\ → una sola barra (o ignorar si es decorativa)
                i++;
                continue; // ← cambia continue por resultado += '\\'; si quieres conservarla
            }
        }
        resultado += texto[i];
    }
    return resultado;
}

// Genera el JSON de la peticion incluyendo caja_id con la IP local
string armarJsonPeticion(const string &accion,
                         const vector<ItemCarrito> &items,
                         const string &cajaId)
{
    string json = "{\"token\":\"" TOKEN_AUTH "\","
                  "\"caja_id\":\"" +
                  cajaId + "\","
                           "\"accion\":\"" +
                  accion + "\","
                           "\"productos\":[";

    for (size_t i = 0; i < items.size(); i++)
    {
        json += "{\"codigo\":\"" + items[i].codigo +
                "\",\"cantidad\":" + to_string(items[i].cantidad) + "}";
        if (i < items.size() - 1)
            json += ",";
    }
    json += "]}";
    return json;
}

// Abre la conexion HTTP, envia el payload y retorna el cuerpo JSON limpio
string ejecutarPeticionHttp(const string &jsonPayload)
{
    SOCKET sock = socket(AF_INET, SOCK_STREAM, 0);
    sockaddr_in serverAddr;
    serverAddr.sin_family = AF_INET;
    serverAddr.sin_port = htons(PUERTO);
    inet_pton(AF_INET, SERVIDOR_IP, &serverAddr.sin_addr);

    if (connect(sock, (sockaddr *)&serverAddr, sizeof(serverAddr)) == SOCKET_ERROR)
    {
        closesocket(sock);
        return "CONEXION_FALLIDA";
    }

    string request = "POST /api/carrito HTTP/1.1\r\n";
    request += "Host: " + string(SERVIDOR_IP) + ":" + to_string(PUERTO) + "\r\n";
    request += "Content-Type: application/json; charset=UTF-8\r\n";
    request += "Content-Length: " + to_string(jsonPayload.length()) + "\r\n";
    request += "Connection: close\r\n\r\n" + jsonPayload;

    send(sock, request.c_str(), (int)request.length(), 0);

    string respuestaFull = "";
    char buffer[4096];
    int bytes;
    while ((bytes = recv(sock, buffer, sizeof(buffer) - 1, 0)) > 0)
    {
        buffer[bytes] = '\0';
        respuestaFull += buffer;
    }
    closesocket(sock);

    size_t posJson = respuestaFull.find("\r\n\r\n");
    if (posJson != string::npos)
        return respuestaFull.substr(posJson + 4);

    return respuestaFull;
}

// Imprime la respuesta final del servidor de forma legible
void imprimirRespuestaFinal(const string &respuestaFinal)
{
    cout << endl
         << "=================== RESPUESTA DEL SERVIDOR CENTRAL ===================" << endl;

    size_t posTicket = respuestaFinal.find("\"ticket\":\"");

    if (posTicket != string::npos)
    {
        if (respuestaFinal.find("\"status\":\"SUCCESS\"") != string::npos)
            cout << "[OK] Venta registrada exitosamente." << endl;

        size_t posBoleta = respuestaFinal.find("\"boleta_id\":");
        if (posBoleta != string::npos)
        {
            size_t inicio = posBoleta + 12;
            size_t fin = respuestaFinal.find_first_of(",}", inicio);
            cout << "Boleta Nro : #" << respuestaFinal.substr(inicio, fin - inicio) << endl;
        }

        size_t inicioTicket = posTicket + 10;
        size_t finTicket = respuestaFinal.find("\",\"total\"", inicioTicket);
        if (finTicket == string::npos)
            finTicket = respuestaFinal.find("\"}", inicioTicket);

        string ticketRaw = respuestaFinal.substr(inicioTicket, finTicket - inicioTicket);
        string ticketFormateado = procesarEscapes(ticketRaw);

        cout << "\n"
             << ticketFormateado << endl;
    }
    else
    {
        cout << respuestaFinal << endl;
    }

    cout << "======================================================================" << endl;
}

int main()
{
#ifdef _WIN32
    SetConsoleOutputCP(CP_UTF8);
    setvbuf(stdout, NULL, _IOFBF, 1000);
#endif

    WSADATA wsaData;
    if (WSAStartup(MAKEWORD(2, 2), &wsaData) != 0)
    {
        cout << "[ERROR] Fallo al inicializar Winsock." << endl;
        return -1;
    }

    // Detectar IP local una sola vez al arrancar
    string ipLocal = obtenerIpLocal();
    string cajaId = "CAJA-" + ipLocal;

    cout << "============================================================" << endl;
    cout << "  AUTOSERVICIO GREEN — Terminal de Ventas                   " << endl;
    cout << "  ID de caja : " << cajaId << endl;
    cout << "  Servidor   : " << SERVIDOR_IP << ":" << PUERTO << endl;
    cout << "============================================================" << endl
         << endl;

    char iniciarNuevaBoleta = 's';
    while (iniciarNuevaBoleta == 's' || iniciarNuevaBoleta == 'S')
    {
        vector<ItemCarrito> carrito;
        string codigo;
        int cantidad;

        cout << "============= NUEVA TRANSACCION (CARRITO EN CONSOLA) =============" << endl;

        while (true)
        {
            cout << "  Codigo de barras (o '0' para finalizar): ";
            cin >> codigo;

            if (codigo == "0")
            {
                if (carrito.empty())
                {
                    cout << "  [!] El carrito esta vacio. Ingrese al menos un producto valido." << endl;
                    continue;
                }
                break;
            }

            cout << "  Cantidad: ";
            while (!(cin >> cantidad) || cantidad <= 0 || cantidad > 999)
            {
                cout << "  [!] Cantidad invalida (1-999). Ingrese de nuevo: ";
                cin.clear();
                cin.ignore(1000, '\n');
            }

            // Verificar en tiempo real sin descontar stock
            vector<ItemCarrito> itemTemporal = {{codigo, cantidad}};
            string jsonVerificar = armarJsonPeticion("VERIFICAR", itemTemporal, cajaId);
            string resServer = ejecutarPeticionHttp(jsonVerificar);

            if (resServer == "CONEXION_FALLIDA")
            {
                cout << "  [CRITICO] No se pudo conectar al Servidor Central. El Gateway esta encendido?" << endl
                     << endl;
                continue;
            }

            if (resServer.find("error") != string::npos || resServer.find("ERROR") != string::npos)
            {
                cout << "  [RECHAZADO] " << resServer << endl;
                cout << "  [!] El producto NO se agrego. Intente con un codigo valido." << endl
                     << endl;
            }
            else
            {
                carrito.push_back({codigo, cantidad});
                cout << "  [+] Codigo verificado en MySQL. Items en carrito: " << carrito.size() << endl
                     << endl;
            }
        }

        cout << endl
             << "[PROCESANDO] Generando venta maestro-detalle en el Servidor Central..." << endl;

        string jsonPagar = armarJsonPeticion("PAGAR", carrito, cajaId);
        string respuestaFinal = ejecutarPeticionHttp(jsonPagar);

        imprimirRespuestaFinal(respuestaFinal);

        cout << endl
             << "Desea atender a un nuevo cliente? (s/n): ";
        cin >> iniciarNuevaBoleta;
        cout << endl;
    }

    WSACleanup();
    cout << "[FIN] Sesion de caja terminada. (" << cajaId << ")" << endl;
    return 0;
}