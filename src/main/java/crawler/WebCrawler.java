package crawler;

import java.io.IOException;
import java.net.URI;
import java.sql.SQLException;
import java.util.*;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;

import dbmanager.DocumentManager;

public class WebCrawler {
    static final int MAX_DEPTH = 3, MAX_PAGES = 200;
    static final int MIN_TEXT_LENGTH = 200;
    static final long DELAY_MS = 500;
    static final String DOMAIN = "timesofindia.indiatimes.com";
    static final String USER_AGENT = "Mozilla/5.0 (compatible; MyCrawler/1.0)";
    static final String SKIP =
        ".*\\.(jpg|jpeg|png|gif|webp|svg|pdf|zip|mp4|css|js)$";

    // Tried in order; replace/add selectors after inspecting a real article page
    static final String[] ARTICLE_SELECTORS = {
        "[itemprop=articleBody]", "article", "main"
    };
    // Elements removed from the article before reading its text
    static final String JUNK =
        "script, style, noscript, nav, header, footer, aside, form, "
        + "iframe, button, .ads, .advertisement, .social-share, "
        + ".related, .comments";

    static class QItem {
        final String url; final int depth;
        QItem(String u, int d) { url = u; depth = d; }
    }

    static class CrawledPage {
        String url, title, description, text;
    }

    static class Stats {
        int attempted, ok, httpErrors, nonHtml, offsiteRedirects,
            networkErrors, extracted, noContent;

        void print(int queueLeft) {
            int failed = httpErrors + nonHtml + offsiteRedirects + networkErrors;
            System.out.println("---- Crawl statistics ----");
            System.out.println("Requests attempted : " + attempted);
            System.out.println("Fetched OK (HTML)  : " + ok);
            System.out.println("Failed total       : " + failed);
            System.out.println("  HTTP errors      : " + httpErrors);
            System.out.println("  Not HTML         : " + nonHtml);
            System.out.println("  Offsite redirect : " + offsiteRedirects);
            System.out.println("  Network errors   : " + networkErrors);
            System.out.println("Articles extracted : " + extracted);
            System.out.println("No usable content  : " + noContent);
            System.out.println("URLs left in queue : " + queueLeft);
        }
    }

    static final Stats stats = new Stats();

    public static void main(String[] args) throws Exception {
        List<CrawledPage> pages =
            crawl("https://timesofindia.indiatimes.com/");
        System.out.println("Articles extracted: " + pages.size());
    }

    static List<CrawledPage> crawl(String start) throws InterruptedException {
        Set<String> queued = new HashSet<>();
        Queue<QItem> queue = new ArrayDeque<>();
        List<CrawledPage> results = new ArrayList<>();

        // 1. Validate the starting URL before anything is queued
        String first = normalize(start);
        if (first == null) {
            System.out.println("Invalid start URL (or outside "
                + DOMAIN + "): " + start);
            return results;
        }
        queued.add(first);
        queue.add(new QItem(first, 1));

        while (!queue.isEmpty() && stats.attempted < MAX_PAGES) {
            QItem item = queue.poll();
            stats.attempted++;

            Document doc = fetch(item.url);
            Thread.sleep(DELAY_MS); // 4. delay applies after failures too
            if (doc == null) continue;


		CrawledPage p = extract(item.url, doc);
		
		if (p.text != null && p.text.length() >= MIN_TEXT_LENGTH) {
		    results.add(p);
		    stats.extracted++;
		
		    // Save the extracted page to the database
		    try {
		        DocumentManager.saveDocument(
		            p.url,
		            p.title,
		            p.description,
		            p.text
		        );
		        System.out.println("Saved to database: " + p.url);
		
		    } catch (SQLException e) {
		        System.err.println(
		            "Failed to save " + p.url + ": " + e.getMessage()
		        );
		    }
		
		} else {
		    stats.noContent++;
		}


            if (item.depth < MAX_DEPTH) {
                for (Element a : doc.select("a[href]")) {
                    String link = normalize(a.absUrl("href"));
                    if (link != null && queued.add(link))
                        queue.add(new QItem(link, item.depth + 1));
                }
            }
        }
        stats.print(queue.size());
        return results;
    }

    // 3. Check status code and content type before parsing
    static Document fetch(String url) {
        try {
            Connection.Response res = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(15000)
                .ignoreHttpErrors(true)   // we check the status ourselves
                .ignoreContentType(true)  // we check the type ourselves
                .execute();

            int code = res.statusCode();
            if (code != 200) {
                stats.httpErrors++;
                System.out.println("HTTP " + code + ": " + url);
                return null;
            }

            String type = res.contentType();
            String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
            if (!t.startsWith("text/html")
                    && !t.startsWith("application/xhtml")) {
                stats.nonHtml++;
                System.out.println("Not HTML (" + type + "): " + url);
                return null;
            }

            // A redirect may have taken us to another site
            if (normalize(res.url().toString()) == null) {
                stats.offsiteRedirects++;
                System.out.println("Redirected off-site: " + url);
                return null;
            }

            stats.ok++;
            return res.parse();
        } catch (IOException e) {
            stats.networkErrors++;
            System.out.println("FAILED " + url + ": " + e.getMessage());
            return null;
        }
    }

    static CrawledPage extract(String url, Document doc) {
        CrawledPage p = new CrawledPage();
        p.url = url;
        p.title = meta(doc, "meta[property=og:title]");
        if (p.title.isEmpty()) p.title = doc.title();
        p.description = meta(doc, "meta[property=og:description]");
        p.text = extractText(doc);
        return p;
    }

    // 5. Try selectors in priority order, strip junk, prefer the fullest match
    static String extractText(Document doc) {
        String best = "";
        for (String sel : ARTICLE_SELECTORS) {
            String longest = "";
            for (Element el : doc.select(sel)) {
                Element copy = el.clone(); // don't modify the original document
                copy.select(JUNK).remove();
                String text = copy.text();
                if (text.length() > longest.length()) longest = text;
            }
            if (longest.length() >= MIN_TEXT_LENGTH) return longest;
            if (longest.length() > best.length()) best = longest;
        }
        return best;
    }

    static String meta(Document d, String sel) {
        Element e = d.selectFirst(sel);
        return e == null ? "" : e.attr("content");
    }

    // 2. Lowercases scheme/host and validates the domain exactly
    static String normalize(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        try {
            URI u = new URI(url.trim());
            if (u.getScheme() == null || u.getHost() == null) return null;

            String sch = u.getScheme().toLowerCase(Locale.ROOT);
            String host = u.getHost().toLowerCase(Locale.ROOT);

            if (!sch.equals("http") && !sch.equals("https")) return null;
            if (!host.equals(DOMAIN) && !host.endsWith("." + DOMAIN))
                return null;

            String path = (u.getPath() == null || u.getPath().isEmpty())
                ? "/" : u.getPath();
            if (path.toLowerCase(Locale.ROOT).matches(SKIP)) return null;

            return new URI("https", host, path, u.getQuery(), null)
                .toString();
        } catch (Exception e) {
            return null;
        }
    }
}