-- Copied verbatim from the monolith's V2__seed_data.sql (users + follows only)
-- Password for all users is: password123
-- BCrypt hash: $2a$10$AbglDchyhkogGBIxNoHdN.pBDK86VNXtF.Vh6N72G9s1rjw7z2b4u

INSERT INTO users (id, username, email, password, bio, image) VALUES
('user-1', 'johndoe', 'john@example.com', '$2a$10$AbglDchyhkogGBIxNoHdN.pBDK86VNXtF.Vh6N72G9s1rjw7z2b4u', 'Full-stack developer and tech enthusiast', 'https://api.dicebear.com/7.x/avataaars/svg?seed=John'),
('user-2', 'janedoe', 'jane@example.com', '$2a$10$AbglDchyhkogGBIxNoHdN.pBDK86VNXtF.Vh6N72G9s1rjw7z2b4u', 'Software architect passionate about clean code', 'https://api.dicebear.com/7.x/avataaars/svg?seed=Jane'),
('user-3', 'bobsmith', 'bob@example.com', '$2a$10$AbglDchyhkogGBIxNoHdN.pBDK86VNXtF.Vh6N72G9s1rjw7z2b4u', 'DevOps engineer and cloud enthusiast', 'https://api.dicebear.com/7.x/avataaars/svg?seed=Bob');

INSERT INTO follows (user_id, follow_id) VALUES
('user-1', 'user-2'),
('user-2', 'user-1'),
('user-3', 'user-1'),
('user-3', 'user-2');
