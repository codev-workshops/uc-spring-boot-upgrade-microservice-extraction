-- Insert sample users
-- Password for all users is: password123
-- BCrypt hash: $2a$10$AbglDchyhkogGBIxNoHdN.pBDK86VNXtF.Vh6N72G9s1rjw7z2b4u

INSERT INTO users (id, username, email, password, bio, image) VALUES
('user-1', 'johndoe', 'john@example.com', '$2a$10$AbglDchyhkogGBIxNoHdN.pBDK86VNXtF.Vh6N72G9s1rjw7z2b4u', 'Full-stack developer and tech enthusiast', 'https://api.dicebear.com/7.x/avataaars/svg?seed=John'),
('user-2', 'janedoe', 'jane@example.com', '$2a$10$AbglDchyhkogGBIxNoHdN.pBDK86VNXtF.Vh6N72G9s1rjw7z2b4u', 'Software architect passionate about clean code', 'https://api.dicebear.com/7.x/avataaars/svg?seed=Jane'),
('user-3', 'bobsmith', 'bob@example.com', '$2a$10$AbglDchyhkogGBIxNoHdN.pBDK86VNXtF.Vh6N72G9s1rjw7z2b4u', 'DevOps engineer and cloud enthusiast', 'https://api.dicebear.com/7.x/avataaars/svg?seed=Bob');

-- Insert sample tags
INSERT INTO tags (id, name) VALUES
('tag-1', 'java'),
('tag-2', 'spring-boot'),
('tag-3', 'web-development'),
('tag-4', 'tutorial'),
('tag-5', 'best-practices'),
('tag-6', 'microservices'),
('tag-7', 'api-design');

-- Insert sample articles
INSERT INTO articles (id, user_id, slug, title, description, body, created_at, updated_at) VALUES
('article-1', 'user-1', 'getting-started-with-spring-boot', 'Getting Started with Spring Boot', 'A comprehensive guide to building your first Spring Boot application', 'Spring Boot makes it easy to create stand-alone, production-grade Spring based Applications that you can "just run". In this article, we will explore the fundamentals of Spring Boot and build a simple REST API.\n\n## Prerequisites\n- Java 11 or higher\n- Basic understanding of Spring Framework\n- Maven or Gradle\n\n## Creating Your First Application\nStart by visiting start.spring.io and selecting your dependencies...', datetime('now', '-7 days'), datetime('now', '-7 days')),

('article-2', 'user-2', 'rest-api-best-practices', 'REST API Best Practices', 'Learn the essential principles for designing robust REST APIs', 'Building a great REST API requires more than just exposing endpoints. In this article, we''ll cover the best practices that will make your API intuitive, maintainable, and scalable.\n\n## Key Principles\n1. Use proper HTTP methods\n2. Implement consistent naming conventions\n3. Version your API\n4. Handle errors gracefully\n5. Document everything\n\nLet''s dive into each principle...', datetime('now', '-5 days'), datetime('now', '-5 days')),

('article-3', 'user-1', 'microservices-architecture-guide', 'Microservices Architecture Guide', 'Understanding microservices patterns and when to use them', 'Microservices architecture has become increasingly popular, but it''s not a silver bullet. This guide will help you understand when and how to implement microservices effectively.\n\n## What are Microservices?\nMicroservices are an architectural style that structures an application as a collection of loosely coupled services...\n\n## Benefits\n- Independent deployment\n- Technology diversity\n- Fault isolation\n- Scalability', datetime('now', '-3 days'), datetime('now', '-3 days')),

('article-4', 'user-3', 'docker-for-java-developers', 'Docker for Java Developers', 'Containerize your Java applications with Docker', 'Docker has revolutionized how we deploy applications. In this tutorial, we''ll learn how to containerize a Spring Boot application and deploy it using Docker.\n\n## Why Docker?\n- Consistent environments\n- Easy deployment\n- Isolation\n- Portability\n\n## Creating a Dockerfile\nHere''s a simple Dockerfile for a Spring Boot app...', datetime('now', '-2 days'), datetime('now', '-2 days')),

('article-5', 'user-2', 'testing-spring-boot-applications', 'Testing Spring Boot Applications', 'A complete guide to testing strategies in Spring Boot', 'Testing is crucial for maintaining code quality. This article covers unit testing, integration testing, and end-to-end testing in Spring Boot applications.\n\n## Testing Layers\n1. Unit Tests with JUnit and Mockito\n2. Integration Tests with @SpringBootTest\n3. API Tests with MockMvc\n4. Database Tests with @DataJpaTest\n\nLet''s explore each testing strategy...', datetime('now', '-1 days'), datetime('now', '-1 days'));

-- Link articles to tags
INSERT INTO article_tags (article_id, tag_id) VALUES
('article-1', 'tag-1'),
('article-1', 'tag-2'),
('article-1', 'tag-4'),
('article-2', 'tag-3'),
('article-2', 'tag-5'),
('article-2', 'tag-7'),
('article-3', 'tag-2'),
('article-3', 'tag-6'),
('article-3', 'tag-5'),
('article-4', 'tag-1'),
('article-4', 'tag-2'),
('article-4', 'tag-4'),
('article-5', 'tag-1'),
('article-5', 'tag-2'),
('article-5', 'tag-5');

-- Add some favorites
INSERT INTO article_favorites (article_id, user_id) VALUES
('article-1', 'user-2'),
('article-1', 'user-3'),
('article-2', 'user-1'),
('article-3', 'user-2'),
('article-4', 'user-1'),
('article-5', 'user-3');

-- Add some follows
INSERT INTO follows (user_id, follow_id) VALUES
('user-1', 'user-2'),
('user-2', 'user-1'),
('user-3', 'user-1'),
('user-3', 'user-2');

-- Add some comments
INSERT INTO comments (id, body, article_id, user_id, created_at, updated_at) VALUES
('comment-1', 'Great article! This really helped me understand Spring Boot basics.', 'article-1', 'user-2', datetime('now', '-6 days'), datetime('now', '-6 days')),
('comment-2', 'Thanks for sharing. The code examples are very clear.', 'article-1', 'user-3', datetime('now', '-6 days'), datetime('now', '-6 days')),
('comment-3', 'Excellent best practices guide. I''ll be implementing these in my project.', 'article-2', 'user-1', datetime('now', '-4 days'), datetime('now', '-4 days')),
('comment-4', 'Very comprehensive overview of microservices. Well written!', 'article-3', 'user-2', datetime('now', '-2 days'), datetime('now', '-2 days')),
('comment-5', 'Docker tutorial was exactly what I needed. Thanks!', 'article-4', 'user-1', datetime('now', '-1 days'), datetime('now', '-1 days'));
