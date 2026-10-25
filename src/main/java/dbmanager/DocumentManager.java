
package dbmanager;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class DocumentManager {

    private DocumentManager() {
        // Prevent object creation.
    }

    public static void saveDocument(
            String url,
            String title,
            String description,
            String body) throws SQLException {

        String sql = " INSERT INTO documents "
        		+ "(url, title, source, domain, snippet, body, doc_length) "
        		+ "VALUES (?, ?, ?, ?, ?, ?, 0) "
        		+ "ON DUPLICATE KEY UPDATE "
        		+ "title = ?, source = ?, domain = ?, snippet = ?, body = ?, doc_length = 0";

        String safeTitle = truncate(title, 300);

        String safeDescription =
            description == null ? "" : description.trim();

        String safeBody =
            body == null ? "" : body.trim();

        String domain = URI.create(url).getHost();

        String snippet   = truncate(safeDescription, 300);

        if (snippet.isBlank() && !safeBody.isBlank()) {
            snippet = safeBody.substring(
                0, Math.min(250, safeBody.length())
            );
        }

        try (Connection con =
                    DatabaseConnection.getConnection();
             PreparedStatement ps =
                    con.prepareStatement(sql)) {

            // INSERT values
            ps.setString(1, url);
            ps.setString(2, safeTitle);
            ps.setString(3, "Times of India");
            ps.setString(4, domain);
            ps.setString(5, snippet);
            ps.setString(6, safeBody);

            // UPDATE values if URL already exists
            ps.setString(7, safeTitle);
            ps.setString(8, "Times of India");
            ps.setString(9, domain);
            ps.setString(10, snippet);
            ps.setString(11, safeBody);

            ps.executeUpdate();
        }
    }
    private static String truncate(String s, int max) {
    	return s.length() <= max ? s : s.substring(0, max);
    }
}
