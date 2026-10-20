-- The requester confirms each helper after the blood bank tells them: donated or no-show.
-- Only a requester "donated" sets confirmed_donation; the donor's own answer never does.
-- reminder_sent_at: the requester was asked "Did ... donate?" once the arrival window ended.

ALTER TABLE pledges
    ADD COLUMN requester_outcome      text CHECK (requester_outcome IN ('donated', 'no_show')),
    ADD COLUMN requester_confirmed_at timestamptz,
    ADD COLUMN confirmed_donation     boolean NOT NULL DEFAULT false,
    ADD COLUMN reminder_sent_at       timestamptz,
    ADD CONSTRAINT pledges_requester_confirmed CHECK ((requester_outcome IS NULL) = (requester_confirmed_at IS NULL)),
    ADD CONSTRAINT pledges_confirmed_donation CHECK (confirmed_donation = (requester_outcome IS NOT DISTINCT FROM 'donated'));

-- No-shows per donor (Phase 5).
CREATE INDEX pledges_no_show_idx ON pledges (donor_id) WHERE requester_outcome = 'no_show';
-- Pledges that may still need a reminder.
CREATE INDEX pledges_reminder_due_idx ON pledges (committed_at)
    WHERE reminder_sent_at IS NULL AND requester_outcome IS NULL AND status IN ('committed', 'donated');

-- Don't remind about pledges whose window ended before this feature existed.
UPDATE pledges p SET reminder_sent_at = now()
FROM blood_requests r
WHERE r.id = p.request_id AND (p.committed_at + interval '3 hours' <= now() OR r.needed_by <= now());
