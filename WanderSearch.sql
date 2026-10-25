CREATE DATABASE WanderSearchEngine;
USE WanderSearchEngine;

-- 1. documents: one row per crawled page (written by the crawler)
CREATE TABLE documents (
  doc_id      INT AUTO_INCREMENT PRIMARY KEY,
  url         VARCHAR(500) NOT NULL UNIQUE,   -- UNIQUE stops duplicate crawls
  title       VARCHAR(300),
  source      VARCHAR(100),                   -- shown in results, e.g. "MDN Web Docs"
  domain      VARCHAR(100),                   -- shown in results, e.g. "developer.mozilla.org"
  snippet     VARCHAR(300),                   -- short preview text, saved at index time
  body        MEDIUMTEXT,                     -- full page text, used by the indexer
  doc_length  INT NOT NULL DEFAULT 0,         -- number of tokens in the page (BM25 needs it)
  crawled_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 2. terms: one row per distinct word (written by the indexer)
CREATE TABLE terms (
  term_id  INT AUTO_INCREMENT PRIMARY KEY,
  term     VARCHAR(100) NOT NULL UNIQUE,      -- UNIQUE also creates the lookup index on the word
  df       INT NOT NULL DEFAULT 0             -- document frequency: pages containing this word
);

-- 3. postings: which word appears in which page (the inverted index)
CREATE TABLE postings (
  term_id  INT NOT NULL,
  doc_id   INT NOT NULL,
  tf       INT NOT NULL,                      -- term frequency: occurrences in that page
  PRIMARY KEY (term_id, doc_id),              -- groups each word's posting list together on disk
  KEY idx_postings_doc (doc_id),              -- speeds up re-indexing or deleting one page
  FOREIGN KEY (term_id) REFERENCES terms(term_id)     ON DELETE CASCADE,
  FOREIGN KEY (doc_id)  REFERENCES documents(doc_id)  ON DELETE CASCADE
);

-- 4. index_stats: collection-wide numbers BM25 needs (always exactly one row)
CREATE TABLE index_stats (
  id          TINYINT PRIMARY KEY DEFAULT 1,
  total_docs  INT NOT NULL DEFAULT 0,         -- N
  avg_doc_len DOUBLE NOT NULL DEFAULT 0,      -- avgdl
  CHECK (id = 1)
);
INSERT INTO index_stats (id) VALUES (1);