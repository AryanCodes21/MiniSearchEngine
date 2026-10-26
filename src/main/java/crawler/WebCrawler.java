package crawler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.net.URI;
import org.jsoup.*;
import java.io.IOException;
import java.sql.SQLException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.*;

import dbmanager.DatabaseConnection;
import dbmanager.DocumentManager;
import dbmanager.PostingManager;
public class WebCrawler {

	// ANSI colour codes
	private static final String RESET = "\u001B[0m";
	private static final String CYAN = "\u001B[36m";
	private static final String GREEN = "\u001B[32m";
	private static final String YELLOW = "\u001B[33m";
	private static final String BLUE = "\u001B[34m";
	private static final String RED = "\u001B[31m";


	 // Websites where our crawler begins its search
	private static final List<String> SEED_URLS = Arrays.asList(
		    "https://en.wikipedia.org/",
		    "https://www.bbc.com/",
		    "https://www.theguardian.com/",
		    "https://www.nasa.gov/",
		    "https://www.nationalgeographic.com/",
		    "https://techcrunch.com/",
		    "https://www.khanacademy.org/",
		    "https://arxiv.org/",
		    "https://stackoverflow.com/",
		    "https://developer.mozilla.org/"
		);

    // Domains the crawler is permitted to visit
	private static final Set<String> ALLOWED_DOMAINS = new HashSet<>(
		    Arrays.asList(
		        "en.wikipedia.org",
		        "www.bbc.com",
		        "www.theguardian.com",
		        "www.nasa.gov",
		        "www.nationalgeographic.com",
		        "techcrunch.com",
		        "www.khanacademy.org",
		        "arxiv.org",
		        "stackoverflow.com",
		        "developer.mozilla.org"
		    )
		);
	
    public static void main(String[] args) {

        ArrayList<String> visited = new ArrayList<>();

        System.out.println("=== WanderSearchEngine ===");
        System.out.println("[Phase 1] Starting crawler...");

        for (String url : SEED_URLS) {
            crawl(1, url, visited);
        }

        System.out.println("[Phase 1] Crawling complete.");
        System.out.println("[Phase 2] Starting indexing...");

        try (java.sql.Connection con = DatabaseConnection.getConnection()) {
            PostingManager.indexPendingDocuments(con);
            System.out.println("[Phase 2] Indexing complete.");
        } catch (SQLException e) {
            System.err.println("Indexing failed: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("WanderSearchEngine pipeline finished.");
    }

	private static void crawl(int level,String url,ArrayList<String> v)
	{
		

		    // Stop when maximum depth is reached
		    if (level > 5) {
		        return;
		    }

		    // Skip already visited URLs
		    if (v.contains(url)) {
		        return;
		    }

		    Document doc = request(url, v);

		    // IMPORTANT: Check before accessing doc
		    if (doc == null) {
		        System.out.println(
		            RED + "Skipping unavailable page: " + url + RESET
		        );
		        return;
		    }

		    // Find links on this page
		    for (Element link : doc.select("a[href]")) {

		        String next_link = link.absUrl("href");

		        if (next_link.isEmpty()) {
		            continue;
		        }


		        try {
		            URI uri = URI.create(next_link);

		            String scheme = uri.getScheme();
		            String host = uri.getHost();

		            if (scheme == null ||
		                !(scheme.equalsIgnoreCase("http")
		                  || scheme.equalsIgnoreCase("https"))) {
		                continue;
		            }

		            if (host == null ||
		                !ALLOWED_DOMAINS.contains(host.toLowerCase())) {
		                continue;
		            }

		        } catch (IllegalArgumentException e) {
		            continue;
		        }

		        // Avoid duplicate visits
		        if (!v.contains(next_link)) {
		            crawl(level + 1, next_link, v);
		            
		            
		        
		        }
//		        try(java.sql.Connection con=DatabaseConnection.getConnection();) {
//					PostingManager.indexPendingDocuments(con);
//				} catch (SQLException e) {
//					// TODO Auto-generated catch block
//					e.printStackTrace();
//				}
		}
	}
	private static Document request(String url,ArrayList<String> v)
	{
		try
		{
			Connection con = Jsoup.connect(url)
			        .userAgent("Mozilla/5.0")
			        .timeout(5000);

			Document doc = con.get();
			System.out.println("Page title: " + doc.title());

			System.out.println("article elements: " +
			        doc.select("article").size());

			System.out.println("main elements: " +
			        doc.select("main").size());

			System.out.println("articleBody elements: " +
			        doc.select("[itemprop=articleBody]").size());

			System.out.println("Elements with content-related classes:");

			for (Element element : doc.select("[class]")) {
			    String className = element.className().toLowerCase();

			    if (className.contains("article")
			            || className.contains("story")
			            || className.contains("content")) {

			        System.out.println(element.tagName() + " class=" +
			                element.className());
			    }
			}
			
			if(con.response().statusCode() == 200)
			{
		
				System.out.println(CYAN + "\nURL: " + url + RESET);

				System.out.println(
				    GREEN + "TITLE: " + doc.title() + RESET
				);

				// Step 1: Try Wikipedia's main content container
				Element article = doc.selectFirst("#mw-content-text");

				// Step 2: If not found, try common article containers
				if (article == null) {
				    article = doc.selectFirst(
				        "article, main, [itemprop=articleBody]"
				    );
				}

				// Step 3: If still not found, try common content containers
				if (article == null) {
				    article = doc.selectFirst(
				        "#content, .article-content, .article-body, " +
				        ".story-content, .story-body, .content"
				    );
				}

				if (article != null) {

				    // Clone so we don't modify the original document
				    Element cleanArticle = article.clone();

				    // Remove unwanted elements
				    cleanArticle.select(
				        "script, style, nav, footer, header, " +
				        ".mw-editsection, .navbox, .metadata, " +
				        ".reflist, .reference, .noprint, .toc, " +
				        ".advertisement, .ads, .social-share"
				    ).remove();

				    // Extract the text
				    String content = cleanArticle.text();

				    System.out.println(
				        YELLOW + "\nPAGE CONTENT:\n" + content + RESET
				    );

				    if (content != null && content.length() > 200) {
				        try {
				            String title = doc.title();

				            String description = "";
				            Element meta = doc.selectFirst(
				                "meta[property=og:description]"
				            );

				            if (meta != null) {
				                description = meta.attr("content");
				            }

				            DocumentManager.saveDocument(
				                url,
				                title,
				                description,
				                content
				            );

				            System.out.println(
				                GREEN + "Saved to database: " + url + RESET
				            );

				        } catch (SQLException e) {
				            System.err.println(
				                RED + "Database save failed: "
				                + e.getMessage() + RESET
				            );
				        }
				    }

				    System.out.println(
				        BLUE + "\nTotal characters: " +
				        content.length() + RESET
				    );

				} else {

				    System.out.println(
				        RED + "Main content not found for: " +
				        url + RESET
				    );
				

				}
				
				v.add(url);
				return doc;
			}
		}
		catch(IOException e) {
		return null;
		}
		return null;
		
	}

}