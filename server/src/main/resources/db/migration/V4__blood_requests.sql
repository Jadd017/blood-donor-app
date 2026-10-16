-- A user asking for blood for a patient at a hospital.
-- Open requests past needed_by are marked 'expired' by a background job in the
-- server; queries also treat them as expired so the status is right between runs.

CREATE TABLE blood_requests (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    requester_id bigint NOT NULL REFERENCES users (id),
    hospital_id  integer NOT NULL REFERENCES hospitals (id),
    -- The patient's blood type
    blood_type   text NOT NULL CHECK (blood_type IN ('A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-')),
    component    text NOT NULL CHECK (component IN ('whole_blood', 'red_cells', 'platelets', 'plasma')),
    units_needed integer NOT NULL CHECK (units_needed BETWEEN 1 AND 10),
    needed_by    timestamptz NOT NULL,
    note         text CHECK (char_length(note) <= 200),
    status       text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'fulfilled', 'cancelled', 'expired')),
    created_at   timestamptz NOT NULL DEFAULT now(),
    closed_at    timestamptz,
    CONSTRAINT blood_requests_needed_by_window
        CHECK (needed_by BETWEEN created_at + interval '6 hours' AND created_at + interval '72 hours'),
    CONSTRAINT blood_requests_closed_at_matches_status
        CHECK ((status = 'open') = (closed_at IS NULL))
);

-- Open requests by hospital (for matching in phase 4b) and for the expiry job.
CREATE INDEX blood_requests_open_by_hospital_idx ON blood_requests (hospital_id, needed_by) WHERE status = 'open';
CREATE INDEX blood_requests_open_needed_by_idx ON blood_requests (needed_by) WHERE status = 'open';
-- "My requests", newest first.
CREATE INDEX blood_requests_requester_idx ON blood_requests (requester_id, created_at DESC);
