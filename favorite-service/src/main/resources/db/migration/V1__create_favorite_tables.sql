-- Copied verbatim from the monolith's V1__create_tables.sql: the only table owned by the
-- Favorite domain. The monolith schema has no foreign keys, so no DDL changes are needed.
create table article_favorites (
  article_id varchar(255) not null,
  user_id varchar(255) not null,
  primary key(article_id, user_id)
);
