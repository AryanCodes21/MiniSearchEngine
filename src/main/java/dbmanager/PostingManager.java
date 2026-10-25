package dbmanager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import helpers.Tokenizer;

public final class PostingManager {

    private static final int BATCH_SIZE = 500;

    private PostingManager() { }  // utility class, no instances

    
    public static void indexDocument(Connection conn, int docId, String body) throws SQLException {
        // 1. tokenize and count: {"search"=5, "engin"=3, ...}
        Map<String, Integer> freq = Tokenizer.termFrequencies(body);

        // 2. make sure every word exists in `terms`
        KeywordsManager.insertTerms(conn, freq.keySet());

        // 3. find the term_id of every word
        Map<String, Integer> termIds = getTermIds(conn, freq.keySet());

        // 4. remove old postings of this document (matters when re-indexing)
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM postings WHERE doc_id = ?")) {
            ps.setInt(1, docId);
            ps.executeUpdate();
        }

        // 5. insert the new postings: one row per distinct word in the page
        String insertSql = "INSERT INTO postings (term_id, doc_id, tf) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            int inBatch = 0;
            for (Map.Entry<String, Integer> e : freq.entrySet()) {
                Integer termId = termIds.get(e.getKey());
                if (termId == null) continue;            // should not happen; skip defensively

                ps.setInt(1, termId);
                ps.setInt(2, docId);
                ps.setInt(3, e.getValue());              // tf = occurrences in this page
                ps.addBatch();

                if (++inBatch == BATCH_SIZE) { ps.executeBatch(); inBatch = 0; }
            }
            if (inBatch > 0) ps.executeBatch();
        }

        // 6. save the page length (total tokens) for BM25
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE documents SET doc_length = ? WHERE doc_id = ?")) {
            ps.setInt(1, Tokenizer.length(freq));
            ps.setInt(2, docId);
            ps.executeUpdate();
        }
    }

    public static void indexPendingDocuments(Connection conn) throws SQLException {
        boolean oldAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            // collect the ids first, then load bodies one at a time (saves memory)
            List<Integer> pending = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT doc_id FROM documents WHERE doc_length = 0")) {
                while (rs.next()) pending.add(rs.getInt(1));
            }

            for (int docId : pending) {
                String body = null;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT body FROM documents WHERE doc_id = ?")) {
                    ps.setInt(1, docId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) body = rs.getString(1);
                    }
                }

                try {
                    indexDocument(conn, docId, body);
                    conn.commit();                       // one transaction per document
                } catch (SQLException ex) {
                    conn.rollback();                     // undo this document only
                    throw ex;
                }
            }

            refreshStats(conn);
            conn.commit();
        } finally {
            conn.setAutoCommit(oldAutoCommit);
        }
    }

    public static void refreshStats(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(
                "UPDATE terms t SET df = " +
                "(SELECT COUNT(*) FROM postings p WHERE p.term_id = t.term_id)");

            st.executeUpdate(
                "UPDATE index_stats SET " +
                "total_docs  = (SELECT COUNT(*) FROM documents), " +
                "avg_doc_len = (SELECT COALESCE(AVG(doc_length), 0) FROM documents) " +
                "WHERE id = 1");
        }
    }


    static Map<String, Integer> getTermIds(Connection conn, Collection<String> words) throws SQLException {
        Map<String, Integer> ids = new HashMap<>();
        if (words.isEmpty()) return ids;

        List<String> all = new ArrayList<>(words);
        for (int from = 0; from < all.size(); from += BATCH_SIZE) {
            List<String> chunk = all.subList(from, Math.min(from + BATCH_SIZE, all.size()));

            // one '?' per word in this chunk (values are bound, never concatenated)
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            String sql = "SELECT term_id, term FROM terms WHERE term IN (" + placeholders + ")";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < chunk.size(); i++) ps.setString(i + 1, chunk.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) ids.put(rs.getString("term"), rs.getInt("term_id"));
                }
            }
        }
        return ids;
    }
}