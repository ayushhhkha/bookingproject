CREATE TABLE outbox_events (
    id             BIGSERIAL    PRIMARY KEY,
    event_id       UUID         NOT NULL UNIQUE,  
    aggregate_type VARCHAR(50)  NOT NULL,          
    aggregate_id   BIGINT       NOT NULL,          
    event_type     VARCHAR(50)  NOT NULL,          
    payload        TEXT         NOT NULL,          
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ                     
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (id) WHERE published_at IS NULL;

CREATE TABLE notifications (
    id         BIGSERIAL    PRIMARY KEY,
    event_id   UUID         NOT NULL UNIQUE,
    user_id    BIGINT       NOT NULL REFERENCES users (id),
    type       VARCHAR(50)  NOT NULL,
    message    VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_user ON notifications (user_id);