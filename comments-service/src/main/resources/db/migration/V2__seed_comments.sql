INSERT INTO comments (id, body, article_id, user_id, created_at, updated_at) VALUES
('comment-1', 'Great article! This really helped me understand Spring Boot basics.', 'article-1', 'user-2', datetime('now', '-6 days'), datetime('now', '-6 days')),
('comment-2', 'Thanks for sharing. The code examples are very clear.', 'article-1', 'user-3', datetime('now', '-6 days'), datetime('now', '-6 days')),
('comment-3', 'Excellent best practices guide. I''ll be implementing these in my project.', 'article-2', 'user-1', datetime('now', '-4 days'), datetime('now', '-4 days')),
('comment-4', 'Very comprehensive overview of microservices. Well written!', 'article-3', 'user-2', datetime('now', '-2 days'), datetime('now', '-2 days')),
('comment-5', 'Docker tutorial was exactly what I needed. Thanks!', 'article-4', 'user-1', datetime('now', '-1 days'), datetime('now', '-1 days'));
