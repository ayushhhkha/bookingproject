CREATE TABLE resources (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL
);

CREATE TABLE slots (
    id          BIGSERIAL PRIMARY KEY,
    resource_id BIGINT      NOT NULL REFERENCES resources (id),
    start_time  TIMESTAMPTZ NOT NULL,
    end_time    TIMESTAMPTZ NOT NULL,
    CHECK (end_time > start_time)
);

CREATE TABLE bookings (
    id            BIGSERIAL PRIMARY KEY,
    slot_id       BIGINT       NOT NULL REFERENCES slots (id),
    customer_name VARCHAR(120) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- THE key rule: only ONE confirmed booking per slot.
-- Cancelled bookings are ignored, so a cancelled slot can be booked again.
CREATE UNIQUE INDEX one_confirmed_booking_per_slot
    ON bookings (slot_id) WHERE status = 'CONFIRMED';
