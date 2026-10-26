package Engine;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import dbmanager.DatabaseConnection;
import helpers.Tokenizer;

@WebServlet("/search")
public class Servlet extends HttpServlet {

private static final long serialVersionUID = 1L;

// BM25 parameters
private static final double K1 = 1.5;
private static final double B = 0.75;

// Represents one webpage in the search results
private static class SearchResult {

    int docId;
    String title;
    String url;
    String snippet;
    String source;
    String domain;
    double score;

    SearchResult(int docId, String title, String url,
                 String snippet, String source,
                 String domain, double score) {

        this.docId = docId;
        this.title = title;
        this.url = url;
        this.snippet = snippet;
        this.source = source;
        this.domain = domain;
        this.score = score;
    }
}

// Handles GET requests from the browser
@Override
protected void doGet(HttpServletRequest request,
                     HttpServletResponse response)
        throws ServletException, IOException {

    request.setCharacterEncoding("UTF-8");
    response.setCharacterEncoding("UTF-8");
    response.setContentType("application/json");

    // Read the search query
    String query = request.getParameter("q");
    // Read pagination parameters
    int page = 1;
    int pageSize = 10;

    try {
    		page = Integer.parseInt(request.getParameter("page"));
    	} 	catch (NumberFormatException | NullPointerException e) {
    		page = 1;
    }

 if (page < 1) {
     page = 1;
 }

    // Validate the query
    if (query == null || query.trim().isEmpty()) {

        response.setStatus(
            HttpServletResponse.SC_BAD_REQUEST
        );

        response.getWriter().write(
            "{\"error\":\"Please enter a search query.\"}"
        );

        return;
    }

    // Connect to MySQL
    try (Connection con = DatabaseConnection.getConnection()) {

        // STEP 1: Tokenize the query
        Map<String, Integer> queryTerms =
            Tokenizer.termFrequencies(query);

        if (queryTerms == null || queryTerms.isEmpty()) {
            writeResults(
                response, query, new ArrayList<>(),
                0, page, pageSize
            );
            return;
        }

        // STEP 2: Read collection statistics
        int totalDocs;
        double avgDocLength;

        String statsSql =
            "SELECT total_docs, avg_doc_len " +
            "FROM index_stats WHERE id = 1";

        try (PreparedStatement ps =
                 con.prepareStatement(statsSql);
             ResultSet rs = ps.executeQuery()) {

        	if (!rs.next()) {
        	    writeResults(
        	        response, query, new ArrayList<>(),
        	        0, page, pageSize
        	    );
        	    return;
        	}

            totalDocs = rs.getInt("total_docs");
            avgDocLength = rs.getDouble("avg_doc_len");
        }

        // Protect against invalid statistics
        if (totalDocs <= 0 || avgDocLength <= 0) {
            writeResults(
                response, query, new ArrayList<>(),
                0, page, pageSize
            );
            return;
        }

        // STEP 3: Prepare a map to collect document scores
        Map<Integer, SearchResult> results =
            new LinkedHashMap<>();

        // Find documents containing each query term
        String searchSql =
        	    "SELECT d.doc_id, d.title, d.url, d.snippet, " +
        	    "d.source, d.domain, d.doc_length, t.df, p.tf " +
        	    "FROM terms t " +
        	    "JOIN postings p ON p.term_id = t.term_id " +
        	    "JOIN documents d ON d.doc_id = p.doc_id " +
        	    "WHERE t.term = ? AND d.doc_length > 0";

        try (PreparedStatement ps =
                 con.prepareStatement(searchSql)) {

            // STEP 4: Process each query term
            for (Map.Entry<String, Integer> entry
                    : queryTerms.entrySet()) {

                String term = entry.getKey();
                int queryTermFrequency = entry.getValue();

                ps.setString(1, term);

                try (ResultSet rs = ps.executeQuery()) {

                    while (rs.next()) {

                        int docId = rs.getInt("doc_id");
                        int tf = rs.getInt("tf");
                        int df = rs.getInt("df");
                        int docLength =
                            rs.getInt("doc_length");

                        // STEP 5: Calculate IDF
                        double idf = Math.log(
                            1.0 +
                            (totalDocs - df + 0.5)
                            / (df + 0.5)
                        );

                        // STEP 6: Calculate BM25 denominator
                        double denominator =
                            tf + K1 * (
                                1.0 - B
                                + B * docLength / avgDocLength
                            );

                        // STEP 7: Calculate this term's score
                        double termScore =
                            idf * (tf * (K1 + 1.0))
                            / denominator;

                        // STEP 8: Find or create the result
                   
                        SearchResult result = results.get(docId);

                        if (result == null) {

                            result = new SearchResult(
                                docId,
                                rs.getString("title"),
                                rs.getString("url"),
                                rs.getString("snippet"),
                                rs.getString("source"),
                                rs.getString("domain"),
                                0.0
                            );

                            results.put(docId, result);
                        }
                 


                        // Add this term's contribution
                        result.score +=
                            queryTermFrequency * termScore;
                    }
                }
            }
        }

     // STEP 9: Sort from highest score to lowest
        List<SearchResult> ranked =
            new ArrayList<>(results.values());

        ranked.sort(
            Comparator.comparingDouble(
                (SearchResult r) -> r.score
            ).reversed()
        );

        // STEP 10: Calculate pagination
        int totalHits = ranked.size();

        long startLong = (long) (page - 1) * pageSize;

        if (startLong >= totalHits) {
            writeResults(
                response, query, new ArrayList<>(),
                totalHits, page, pageSize
            );
            return;
        }

        int start = (int) startLong;
        int end = Math.min(start + pageSize, totalHits);

        List<SearchResult> pageResults =
            new ArrayList<>(ranked.subList(start, end));

        // STEP 11: Return this page as JSON
        writeResults(
            response, query, pageResults,
            totalHits, page, pageSize
        );

    } catch (SQLException e) {

        // Log the detailed error on the server
        getServletContext().log(
            "Database error during search", e
        );

        response.setStatus(
            HttpServletResponse.SC_INTERNAL_SERVER_ERROR
        );

        response.getWriter().write(
            "{\"error\":\"A database error occurred.\"}"
        );
    }
}

// Converts search results into a JSON response
private void writeResults(
        HttpServletResponse response,
        String query,
        List<SearchResult> results,
        int totalHits,
        int page,
        int pageSize) throws IOException {

    StringBuilder json = new StringBuilder();

    json.append("{\"query\":\"")
    .append(escapeJson(query))
    .append("\",")
    .append("\"totalHits\":")
    .append(totalHits)
    .append(",")
    .append("\"page\":")
    .append(page)
    .append(",")
    .append("\"pageSize\":")
    .append(pageSize)
    .append(",")
    .append("\"results\":[");
    for (int i = 0; i < results.size(); i++) {

        SearchResult r = results.get(i);

        if (i > 0) {
            json.append(",");
        }

        json.append("{")
            .append("\"docId\":")
            .append(r.docId)
            .append(",")

            .append("\"title\":\"")
            .append(escapeJson(r.title))
            .append("\",")

            .append("\"url\":\"")
            .append(escapeJson(r.url))
            .append("\",")

            .append("\"snippet\":\"")
            .append(escapeJson(r.snippet))
            .append("\",")
            .append("\"source\":\"")
            .append(escapeJson(r.source))
            .append("\",")
            .append("\"domain\":\"")
            .append(escapeJson(r.domain))
            .append("\",")
            .append("\"score\":")
            .append(
                String.format(
                    Locale.ROOT, "%.6f", r.score
                )
            )

            .append("}");
    }

    json.append("]}");

    response.getWriter().write(json.toString());
}

// Escapes special characters in JSON strings
private String escapeJson(String value) {

    if (value == null) {
        return "";
    }

    StringBuilder escaped = new StringBuilder();

    for (int i = 0; i < value.length(); i++) {

        char c = value.charAt(i);

        switch (c) {

            case '"':
                escaped.append("\\\"");
                break;

            case '\\':
                escaped.append("\\\\");
                break;

            case '\b':
                escaped.append("\\b");
                break;

            case '\f':
                escaped.append("\\f");
                break;

            case '\n':
                escaped.append("\\n");
                break;

            case '\r':
                escaped.append("\\r");
                break;

            case '\t':
                escaped.append("\\t");
                break;

            default:
                if (c < 0x20) {
                    escaped.append(
                        String.format(
                            Locale.ROOT,
                            "\\u%04x",
                            (int) c
                        )
                    );
                } else {
                    escaped.append(c);
                }
        }
    }

    return escaped.toString();
}

}
