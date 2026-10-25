
package dbmanager;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class DatabaseConnection {

    private static final String URL =
        System.getenv().getOrDefault(
            "DB_URL",
            "jdbc:mysql://localhost:3306/WanderSearchEngine"
        );

    private static final String USER =
        System.getenv().getOrDefault("DB_USER", "root");

    private static final String PASSWORD =
        System.getenv().getOrDefault("DB_PASSWORD", "");

    private DatabaseConnection() {
        // Prevent object creation.
    }

    public static Connection getConnection()
            throws SQLException {
        return DriverManager.getConnection(
            URL, USER, PASSWORD
        );
    }
}
