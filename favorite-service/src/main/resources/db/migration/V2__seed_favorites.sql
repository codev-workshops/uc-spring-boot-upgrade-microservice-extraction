-- The article_favorites rows from the monolith's V2__seed_data.sql (ids reference articles/users
-- that live in the monolith's dev.db).
INSERT INTO article_favorites (article_id, user_id) VALUES
('article-1', 'user-2'),
('article-1', 'user-3'),
('article-2', 'user-1'),
('article-3', 'user-2'),
('article-4', 'user-1'),
('article-5', 'user-3');
