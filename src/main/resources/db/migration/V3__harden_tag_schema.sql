-- Make tags a constrained first-class entity.
-- Flyway runs this script in a single transaction on SQLite: if any statement fails, nothing is applied.
-- Column names are unchanged, so application code written against V1 keeps working during rollout.
-- Rollback: db/rollback/U3__harden_tag_schema.sql

-- 1. Normalise tag names (surrounding whitespace produced logical duplicates).
UPDATE tags SET name = trim(name);

-- 2. Keep one link per (article, tag name), pointing at the canonical tag (lowest id per name).
--    Links to missing articles/tags and to blank tags are dropped; articles without tags are untouched.
CREATE TABLE article_tags_v3_migration AS
SELECT DISTINCT AT.article_id AS article_id, canonical.id AS tag_id
FROM article_tags AT
JOIN articles A ON A.id = AT.article_id
JOIN tags T ON T.id = AT.tag_id
JOIN (SELECT name, min(id) AS id FROM tags GROUP BY name) canonical ON canonical.name = T.name
WHERE T.name <> '';

-- 3. Rebuild tags with UNIQUE / CHECK constraints, merging duplicates into the canonical row.
CREATE TABLE tags_v3 (
  id varchar(255) primary key,
  name varchar(255) not null UNIQUE CHECK (length(trim(name)) > 0)
);
INSERT INTO tags_v3 (id, name)
SELECT min(id), name FROM tags WHERE name <> '' GROUP BY name;

DROP TABLE article_tags;
DROP TABLE tags;
ALTER TABLE tags_v3 RENAME TO tags;

-- 4. Rebuild the junction table with a composite primary key and foreign keys.
create table article_tags (
  article_id varchar(255) not null REFERENCES articles(id) ON DELETE CASCADE,
  tag_id varchar(255) not null REFERENCES tags(id) ON DELETE CASCADE,
  primary key (article_id, tag_id)
);
INSERT INTO article_tags (article_id, tag_id)
SELECT article_id, tag_id FROM article_tags_v3_migration;
DROP TABLE article_tags_v3_migration;

-- The primary key covers lookups by article; this covers lookups by tag.
CREATE INDEX idx_article_tags_tag_id ON article_tags(tag_id);
