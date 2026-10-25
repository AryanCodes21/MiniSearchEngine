package dbmanager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import helpers.Tokenizer;

public final class KeywordsManager {

    // How many rows to send to MySQL in one batch
    private static final int BATCH_SIZE = 500;

    private static final String INSERT_SQL =
        "INSERT INTO terms (term) VALUES (?) ON DUPLICATE KEY UPDATE term = term";

    private KeywordsManager() { }  // utility class, no instances

    public static void insertTerms(Connection conn, Collection<String> tokens) throws SQLException {
        if (tokens == null || tokens.isEmpty()) return;

        // Remove duplicates inside this call (a page repeats words many times).
        // LinkedHashSet keeps the original order.
        Set<String> distinct = new LinkedHashSet<>(tokens);

        try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            int inBatch = 0;
            for (String term : distinct) {
                ps.setString(1, term);
                ps.addBatch();

                // Send the batch when it is full so memory use stays small
                if (++inBatch == BATCH_SIZE) {
                    ps.executeBatch();
                    inBatch = 0;
                }
            }
            if (inBatch > 0) ps.executeBatch();   // send the remaining rows
        }
    }

    public static void insertTermsFromText(Connection conn, String text) throws SQLException {
        List<String> tokens = Tokenizer.tokenize(text);
        insertTerms(conn, tokens);
    }
}