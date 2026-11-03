CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,         
    name          VARCHAR(120) NOT NULL,
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

DELETE FROM bookings;

ALTER TABLE bookings DROP COLUMN customer_name;
ALTER TABLE bookings ADD COLUMN user_id BIGINT NOT NULL REFERENCES users (id);

CREATE INDEX idx_bookings_user ON bookings (user_id);
