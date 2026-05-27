package com.autoservicio.ventas;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class ConexionBD {
    // Configuración de tu MySQL local
    private static final String URL = "jdbc:mysql://localhost:3306/autoservicio_green?serverTimezone=UTC";
    private static final String USER = "root";
    private static final String PASSWORD = "";

    public static Connection obtenerConexion() {
        Connection conexion = null;
        try {
            // Cargar el driver de MySQL que pusimos en Gradle
            Class.forName("com.mysql.cj.jdbc.Driver");
            conexion = DriverManager.getConnection(URL, USER, PASSWORD);
        } catch (ClassNotFoundException e) {
            System.out.println("[ERROR] No se encontró el driver de MySQL: " + e.getMessage());
        } catch (SQLException e) {
            System.out.println("[ERROR] Error al conectar a MySQL: " + e.getMessage());
        }
        return conexion;
    }
}