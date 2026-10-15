-- One row per Firebase user. Email and firebase_uid always come from the
-- verified Firebase ID token, never from the request body.

CREATE TABLE users (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    firebase_uid       text NOT NULL UNIQUE,
    email              text NOT NULL UNIQUE CHECK (email = lower(email)),
    full_name          text NOT NULL,
    -- E.164 Lebanese mobile, e.g. +96171123456
    phone              text NOT NULL UNIQUE CHECK (phone ~ '^\+961[0-9]{7,8}$'),
    -- NULL = unknown / not donating
    blood_type         text CHECK (blood_type IN ('A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-')),
    sex                text CHECK (sex IN ('male', 'female')),
    area_id            integer REFERENCES areas (id),
    last_donation_date date,
    is_donor           boolean NOT NULL DEFAULT true,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);

-- For matching donors by blood type and area later.
CREATE INDEX users_donor_match_idx ON users (blood_type, area_id) WHERE is_donor;
