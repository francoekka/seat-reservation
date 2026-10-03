CREATE TABLE IF NOT EXISTS reservation_user_locks (
    show_id UUID NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (show_id, user_id)
);

CREATE TABLE IF NOT EXISTS reservations (
    id UUID PRIMARY KEY,
    show_id UUID NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    status VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    seat_ids UUID ARRAY NOT NULL,
    total_price_paise BIGINT NOT NULL
);
