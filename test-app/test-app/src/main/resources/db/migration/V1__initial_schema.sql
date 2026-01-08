-- Create initial table
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Insert some test data
INSERT INTO users (username, email) VALUES
    ('user1', 'user1@example.com'),
    ('user2', 'user2@example.com'),
    ('user3', 'user3@example.com');
