-- Migration: article-service schema (articles, tags, article_tags) extracted from the monolith.
-- articles.user_id references a user owned by the monolith; it is not a local foreign key.

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

create index idx_articles_user_id on articles (user_id);
create index idx_article_tags_article_id on article_tags (article_id);
create index idx_article_tags_tag_id on article_tags (tag_id);
