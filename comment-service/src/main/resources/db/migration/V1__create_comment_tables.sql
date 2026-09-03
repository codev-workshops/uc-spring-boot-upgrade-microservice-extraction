-- Copied verbatim from the monolith's V1__create_tables.sql: the only table owned by the
-- Comment domain. The monolith schema has no foreign keys, so no DDL changes are needed.
create table comments (
  id varchar(255) primary key,
  body text,
  article_id varchar(255),
  user_id varchar(255),
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
