-- Reverses V3__harden_tag_schema.sql. Not on the Flyway location path: run manually, in one transaction.
-- Restores the V1 table shapes (no PK/FK/UNIQUE/CHECK, no index) and keeps all current rows.
-- Tags merged by V3 stay merged; blank/orphan rows dropped by V3 are not recreated.

create table article_tags_v1 (
  article_id varchar(255) not null,
  tag_id varchar(255) not null
);
INSERT INTO article_tags_v1 (article_id, tag_id) SELECT article_id, tag_id FROM article_tags;

create table tags_v1 (
  id varchar(255) primary key,
  name varchar(255) not null
);
INSERT INTO tags_v1 (id, name) SELECT id, name FROM tags;

DROP TABLE article_tags;
DROP TABLE tags;
ALTER TABLE tags_v1 RENAME TO tags;
ALTER TABLE article_tags_v1 RENAME TO article_tags;

DELETE FROM flyway_schema_history WHERE version = '3';
