-- Copied verbatim from the monolith's V1__create_tables.sql: the tables owned by the Article
-- domain. `articles` is created now (Phase 3) but stays empty until the Phase 4 backfill so the
-- V1__ naming is kept and no later schema migration is needed. No foreign keys, no unique
-- constraint on tags.name — identical semantics to the monolith.
create table articles (
  id varchar(255) primary key,
  user_id varchar(255),
  slug varchar(255) UNIQUE,
  title varchar(255),
  description text,
  body text,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

create table tags (
  id varchar(255) primary key,
  name varchar(255) not null
);

create table article_tags (
  article_id varchar(255) not null,
  tag_id varchar(255) not null
);
