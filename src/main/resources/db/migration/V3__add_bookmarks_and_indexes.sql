-- Bookmarks: join table between users and articles (mirrors article_favorites)
create table bookmarks (
  article_id varchar(255) not null,
  user_id varchar(255) not null,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  primary key(article_id, user_id)
);

create index idx_bookmarks_user_id_created_at on bookmarks(user_id, created_at);

-- Denormalized tag count on articles
alter table articles add column tag_count int not null default 0;

-- Backfill tag_count from existing article_tags rows (e.g. V2 seed data)
update articles
set tag_count = (
  select count(*) from article_tags where article_tags.article_id = articles.id
);

-- Indexes for commonly queried columns (see MyBatis mappers)
create index idx_article_tags_article_id on article_tags(article_id);
create index idx_article_tags_tag_id on article_tags(tag_id);
create index idx_article_favorites_user_id on article_favorites(user_id);
create index idx_articles_user_id_created_at on articles(user_id, created_at);
create index idx_articles_created_at on articles(created_at);
create index idx_comments_article_id_created_at on comments(article_id, created_at);
create index idx_tags_name on tags(name);
