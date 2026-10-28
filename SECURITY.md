# Security

This document describes how BloodDonor protects its users: who can see and change what, and
which limits keep the service from being abused. It contains no secrets: every credential lives
in environment variables or files outside the repository (see `server/.env.example`).

## Reporting a vulnerability

Please don't open a public issue. Email the maintainer (see the GitHub profile) with the details,
and allow reasonable time for a fix before disclosing it.

## Architecture in one paragraph

An Android app (`:app`) signs users in with Firebase Authentication (email/password with email
verification, or Google) and talks to a Ktor server (`:server`) over HTTPS. The server stores
everything in PostgreSQL/PostGIS and sends push notifications through Firebase Cloud Messaging.
The app never talks to the database or to other users directly.

## Authentication

- Every API call except `GET /health` and `GET /api/v1/areas` needs `Authorization: Bearer <Firebase ID token>`.
- The server verifies the token's signature, expiry and project with the Firebase Admin SDK, with
  **`checkRevoked = true`**: a disabled or deleted user, or one whose sessions were revoked, is refused
  with `401`. To avoid a remote call on every request, a successful revocation check is cached per user
  for **5 minutes** (in memory, at most 10,000 users). Deleting an account clears its cache entry at once.
- If Firebase can't be reached to do that check, the answer is `503 auth_unavailable`, not `401`,
  so the app doesn't treat an outage as a sign-out.
- The email address must be verified (`email_verified` claim); otherwise `403 email_not_verified`.
- The user's identity (`uid`, `email`) comes **only** from the verified token, never from a request
  body or URL. Unknown fields in request bodies are ignored, so a body can't set them.

## Authorization

The server is the only authority. Ids in URLs are always checked together with the caller's own
user id; someone else's resource is reported as **404** (not 403), so its existence isn't revealed.
Ids must be positive integers.

| Route | Who may use it |
|---|---|
| `GET/PUT/DELETE /me` | The caller, for their own profile |
| `PUT/DELETE /me/device-token` | The caller; a token belongs to one install and moves to whoever registers it last (shared phones). At most 5 tokens per user |
| `GET /hospitals`, `GET /requests/mine`, `GET /requests/for-me` | Scoped to the caller |
| `POST /requests` | Any user with a profile, within the posting limits below |
| `GET /requests/{id}` | The owner (full view); a donor who was notified about it (donor view); anyone else 404. A hidden request is only visible to donors who committed to it, without its note |
| `POST /requests/{id}/fulfill`, `/cancel` | The owner |
| `POST /requests/{id}/helpers/{pledgeId}/confirm` | The owner, for a pledge on that same request, from 30 minutes after the donor committed (or once the request closed) |
| `POST/DELETE /requests/{id}/help`, `POST /requests/{id}/donation` | A donor who was notified about the request (never the owner), for their own pledge |
| `POST /requests/{id}/report`, `/block-requester` | A donor who was notified about or committed to the request |
| `GET /blocks`, `DELETE /blocks/{userId}` | The caller's own blocks |
| `POST /blocks/{userId}` | Only someone who helped with one of the caller's requests |

## Privacy rules

| Data | Who sees it |
|---|---|
| Email | Only the user themself |
| Phone number | The user themself; and the **requester** of a request the user committed to, as a helper. It is hidden again once either of them blocked the other, and 7 days after the request closed |
| Full name | The user; requesters see their helpers' names. Donors only ever see the requester's **first name**, and only after committing |
| Requester identity | Never shown to donors (blocking a requester works by request, without learning who it is) |
| Request note | Notified donors, while the request isn't hidden. Notes may not contain phone numbers, links or invisible characters |
| Push notifications | Contain the blood type, component, hospital and area only; never a note, name of the requester or phone number |

Other rules:

- Free text shown to other people (names, notes, report details) may not contain control or
  invisible Unicode characters (e.g. bidi overrides, zero-width spaces), so it can't be made to read
  differently from what it is. Names have repeated spaces collapsed.
- Deleting an account (`DELETE /me`, required by Google Play) removes the profile, device tokens,
  blocks and reports, clears the notes of the user's requests, cancels their open requests and
  withdraws their open pledges. Requests and pledges stay for other people's history, without the person.
- Server logs contain ids, never tokens, emails, phone numbers, names or notes. Database error
  details (which can quote a row) are switched off in the JDBC driver and not logged.
- The app keeps no personal data of its own on the device. Android backup and device-to-device
  transfer are disabled, so the Firebase session can't be restored onto another phone.
- Error responses are always `{"error": code, "message": text}` without stack traces or internals.

## Anti-spam and safety rules

All thresholds are environment variables (see `server/.env.example`); defaults shown.

- **Posting:** at most 2 open requests (1 for accounts younger than 7 days) and 3 new requests per
  rolling 24 hours; no duplicate open request for the same hospital, blood type and component.
  These checks run under a row lock, so parallel posts can't slip past them.
- **Reports:** a request reported by 3 different users is hidden, closed, and gives its requester a strike.
- **Strikes:** 1st strike suspends posting for 7 days, a 2nd within 90 days for 30 days, 3 strikes for good.
- **No-shows:** no-shows confirmed by 3 *different* requesters within 90 days pause the donor's
  notifications for 30 days (a single requester can never trigger this alone).
- **Blocks:** work both ways for matching, "I can help" and the request feed.
- **Notifications:** each donor gets at most 3 request notifications per 24 hours, only for
  compatible requests, in waves of growing radius that stop once enough helpers are on the way.
- **Commitments:** a donor can be committed to at most 2 open requests at a time.

## Rate limits

Counted per signed-in user, or per IP address for calls without a valid token (including calls
with an invalid token). Per minute:

| Limit | Default | Routes |
|---|---|---|
| Public | 120 | `GET /areas` (no sign-in needed) |
| General | 120 | Every authenticated route |
| Profile | 10 | `PUT/DELETE /me` |
| Device token | 10 | `PUT/DELETE /me/device-token` |
| Write | 30 | fulfill, cancel, confirm a helper, "Did you donate?" |
| Post request | 5 | `POST /requests` |
| Help | 10 | `POST/DELETE /requests/{id}/help` |
| Report | 5 | `POST /requests/{id}/report` |
| Blocks | 20 | block and unblock routes |

`X-Forwarded-For` is ignored unless the direct peer is listed in `TRUSTED_PROXY_IPS` (your own
reverse proxy), so clients can't pick a fresh IP per call.

## HTTP hardening

- No CORS: the API is for the mobile app only, and browsers get no cross-origin access.
- Response headers: `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`,
  `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `Referrer-Policy: no-referrer`,
  `Strict-Transport-Security: max-age=31536000`.
- Request bodies over 16 KB are refused with `413`, also when sent without `Content-Length`.
- Netty: 15 s request read and response write timeouts, connections idle for 60 s are closed,
  4 KB request line and 8 KB headers at most, and at most `MAX_CONNECTIONS` (500) open connections.
- The server listens on `127.0.0.1` unless `HOST` says otherwise, so in production only the
  reverse proxy on the same machine can reach it (see `deploy/`).
- `GET /health` answers only `OK` or `503 Unavailable`.
- TLS is expected to be terminated in front of the server; the release app only speaks HTTPS
  (cleartext is allowed for `localhost` in debug builds only, and the release build refuses a
  non-HTTPS server URL).
- Release builds are shrunk and obfuscated with R8, have `debuggable=false`, and contain no `Log` calls.

## Database

All SQL uses bound parameters, including the PostGIS queries. The schema is created only by the
Flyway migrations in `server/src/main/resources/db/migration`.

### Database roles (recommended for production)

Run the migrations as a schema owner and the server as a role that can only read and write rows.
Set `DB_MIGRATION_USER` / `DB_MIGRATION_PASSWORD` for the first and `DB_USER` / `DB_PASSWORD` for
the second; without `DB_MIGRATION_USER` the server migrates as `DB_USER`, as before.

```sql
-- As a superuser, once (PostGIS must be installed by a superuser too).
CREATE ROLE blooddonor_migrator LOGIN PASSWORD '...';
CREATE ROLE blooddonor_runtime  LOGIN PASSWORD '...';

ALTER DATABASE blooddonor OWNER TO blooddonor_migrator;
ALTER SCHEMA public OWNER TO blooddonor_migrator;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- Existing objects (needed when moving an existing database; skip on a fresh one).
REASSIGN OWNED BY blooddonor_app TO blooddonor_migrator;  -- the old single role, if any

GRANT CONNECT ON DATABASE blooddonor TO blooddonor_runtime;
GRANT USAGE ON SCHEMA public TO blooddonor_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO blooddonor_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO blooddonor_runtime;
-- Flyway's history table: the runtime role has no business changing it.
REVOKE INSERT, UPDATE, DELETE ON flyway_schema_history FROM blooddonor_runtime;

-- Tables and sequences that future migrations create get the same rights.
ALTER DEFAULT PRIVILEGES FOR ROLE blooddonor_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO blooddonor_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE blooddonor_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO blooddonor_runtime;
```

The runtime role then can't create, alter or drop tables, so a bug in the server can't change the schema.

## Dependencies

Server security floors are pinned in `gradle/libs.versions.toml`: PostgreSQL JDBC 42.7.14,
logback 1.5.38, one Netty version for all modules (via `netty-bom`), and jackson-core 2.18.12 or later.
Keep GitHub Dependabot alerts on for the repository and check advisories before each release.
