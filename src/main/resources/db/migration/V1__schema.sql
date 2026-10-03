-- V1__schema.sql
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE shows (
                       id UUID PRIMARY KEY,
                       name TEXT NOT NULL,
                       total_seats INT NOT NULL CHECK (total_seats >= 0),
                       created_at TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE TABLE seats (
                       id UUID PRIMARY KEY,
                       show_id UUID NOT NULL REFERENCES shows(id) ON DELETE CASCADE,
                       seat_number TEXT NOT NULL,
                       price_paise INT NOT NULL CHECK (price_paise >= 0),
                       status TEXT NOT NULL CHECK (status IN ('AVAILABLE','HELD','CONFIRMED')),
                       reserved_by_user_id TEXT NULL,
                       reservation_id UUID NULL,
                       created_at TIMESTAMP WITH TIME ZONE DEFAULT now(),
                       UNIQUE (show_id, seat_number)
);

CREATE INDEX idx_seats_show_locked_by_user ON seats(show_id, reserved_by_user_id)
    WHERE status IN ('HELD','CONFIRMED');

CREATE TABLE reservations (
                              id UUID PRIMARY KEY,
                              show_id UUID NOT NULL REFERENCES shows(id),
                              user_id TEXT NOT NULL,
                              seat_ids UUID[] NOT NULL,
                              total_price_paise BIGINT NOT NULL,
                              status TEXT NOT NULL CHECK (status IN ('CONFIRMED','CANCELLED')),
                              created_at TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE TABLE idempotency_keys (
                                  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                  key TEXT NOT NULL UNIQUE,
                                  payload_hash TEXT NOT NULL,
                                  status TEXT NOT NULL CHECK (status IN ('PROCESSING','COMPLETED')),
                                  response_json JSONB NULL,
                                  created_at TIMESTAMP WITH TIME ZONE DEFAULT now(),
                                  updated_at TIMESTAMP WITH TIME ZONE DEFAULT now()
);
