-- Phase 5: automatic anti-spam and safety rules, reporting, blocking and account deletion.

-- Set when a donor had no-shows with several different requesters (see SafetyConfig).
ALTER TABLE users ADD COLUMN notifications_paused_until timestamptz;

-- Hidden after enough reports from different users. A hidden request is also closed as cancelled.
ALTER TABLE blood_requests ADD COLUMN hidden boolean NOT NULL DEFAULT false;

CREATE TABLE reports (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id  bigint NOT NULL REFERENCES blood_requests (id) ON DELETE CASCADE,
    reporter_id bigint NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reason      text NOT NULL CHECK (reason IN ('fake', 'money', 'inappropriate', 'other')),
    details     text CHECK (char_length(details) <= 200),
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT reports_once UNIQUE (request_id, reporter_id)
);

-- A strike suspends posting; how long depends on the user's earlier strikes.
CREATE TABLE strikes (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    bigint NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reason     text NOT NULL CHECK (reason IN ('request_hidden')),
    request_id bigint REFERENCES blood_requests (id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX strikes_user_idx ON strikes (user_id, created_at);
-- At most one strike per hidden request.
CREATE UNIQUE INDEX strikes_once_per_request ON strikes (request_id) WHERE request_id IS NOT NULL;

-- Blocking works in both directions for matching and "I can help". via_request_id is set when a
-- donor blocked a requester from a request: the donor never saw who it was, so the block is
-- listed by hospital and date instead of by name.
CREATE TABLE blocks (
    blocker_id     bigint NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    blocked_id     bigint NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    via_request_id bigint REFERENCES blood_requests (id) ON DELETE SET NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT blocks_once PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT blocks_not_self CHECK (blocker_id <> blocked_id)
);

CREATE INDEX blocks_blocked_idx ON blocks (blocked_id);

-- Deleting an account keeps requests and pledges for the other people's history, without
-- the person: the user id becomes NULL (name, phone and email only ever live in users).
ALTER TABLE blood_requests
    ALTER COLUMN requester_id DROP NOT NULL,
    DROP CONSTRAINT blood_requests_requester_id_fkey,
    ADD CONSTRAINT blood_requests_requester_id_fkey FOREIGN KEY (requester_id) REFERENCES users (id) ON DELETE SET NULL;

ALTER TABLE pledges
    ALTER COLUMN donor_id DROP NOT NULL,
    DROP CONSTRAINT pledges_donor_id_fkey,
    ADD CONSTRAINT pledges_donor_id_fkey FOREIGN KEY (donor_id) REFERENCES users (id) ON DELETE SET NULL;
