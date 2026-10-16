-- Push notifications to matching donors (phase 4b).

-- FCM registration tokens. A token belongs to one app install; if another
-- account signs in on the same phone, the token moves to that account.
CREATE TABLE device_tokens (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    bigint NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token      text NOT NULL UNIQUE,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX device_tokens_user_idx ON device_tokens (user_id);

-- Who was notified about which request. The unique key enforces "at most once per request".
CREATE TABLE request_notifications (
    request_id bigint NOT NULL REFERENCES blood_requests (id) ON DELETE CASCADE,
    user_id    bigint NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    wave       smallint NOT NULL CHECK (wave BETWEEN 1 AND 4),
    sent_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT request_notifications_once UNIQUE (request_id, user_id)
);

-- For the "at most N notifications per donor per 24 h" cap and GET /requests/for-me.
CREATE INDEX request_notifications_user_sent_idx ON request_notifications (user_id, sent_at DESC);

-- How many waves have been sent for a request (0 = none yet). Claiming wave n is
-- a conditional update from n - 1 to n, so two workers never send the same wave.
ALTER TABLE blood_requests
    ADD COLUMN waves_sent smallint NOT NULL DEFAULT 0 CHECK (waves_sent BETWEEN 0 AND 4);
