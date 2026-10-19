-- "I can help": a donor commits to go to the hospital for a request.
-- One row per donor and request. Withdrawing and committing again reuses the row,
-- so committed_at is the latest commit (used for the "Did you donate?" timing).

CREATE TABLE pledges (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id   bigint NOT NULL REFERENCES blood_requests (id) ON DELETE CASCADE,
    donor_id     bigint NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status       text NOT NULL DEFAULT 'committed'
                 CHECK (status IN ('committed', 'withdrawn', 'donated', 'not_donated')),
    committed_at timestamptz NOT NULL DEFAULT now(),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pledges_once UNIQUE (request_id, donor_id)
);

-- Helpers of a request (the requester's list and the wave stop rule).
CREATE INDEX pledges_helpers_idx ON pledges (request_id) WHERE status IN ('committed', 'donated');
-- A donor's active pledges (the "at most 2 at a time" rule).
CREATE INDEX pledges_donor_committed_idx ON pledges (donor_id) WHERE status = 'committed';
