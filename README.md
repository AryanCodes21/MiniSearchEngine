# Mini Search Engine

## 1. Introduction
The Mini Search Engine is a Java-based application that crawls web pages, stores their information in a MySQL database, and retrieves relevant documents based on user search queries.

## 2. Objectives
- Crawl web pages and extract useful content.
- Store document data in a MySQL database.
- Build an inverted index for efficient searching.
- Retrieve and rank relevant results.
- Provide a simple, user-friendly search interface.

## 3. Technologies Used
- **Java:** Core application logic.
- **Jsoup:** Web crawling and HTML parsing.
- **MySQL:** Document storage and indexing.
- **JDBC:** Database connectivity.
- **Eclipse IDE:** Development environment.

## 4. System Workflow

1. **Crawling:** Collect web pages and extract their content.
2. **Storage:** Save document information in the database.
3. **Indexing:** Map searchable terms to their documents.
4. **Searching:** Process user queries and retrieve matching documents.
5. **Ranking:** Use BM25, if implemented, to rank results by relevance.
6. **Display:** Show search results through the GUI.

## 5. Database Design
The main tables are:

- **documents:** Stores URLs, titles, snippets, and document metadata.
- **terms:** Stores unique searchable words.
- **postings:** Connects terms with the documents containing them.

Together, these tables support efficient keyword-based retrieval.

## 6. Expected Outcome
The system aims to provide relevant search results from its own locally stored and indexed collection of web pages instead of relying on an external search engine for every query.

## 7. Conclusion
The Mini Search Engine demonstrates the fundamental concepts of web crawling, database management, indexing, and information retrieval using Java and MySQL.
