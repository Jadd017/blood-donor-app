-- Database, roles and PostGIS for the BloodDonor server (the role split in SECURITY.md,
-- "Database roles"). Run as the postgres superuser, with the two passwords as psql variables:
--
--   sudo -u postgres psql -v migrator_password="$MIGRATOR_PW" -v runtime_password="$RUNTIME_PW" < deploy/db-setup.sql
--
-- Safe to run again (it sets the passwords again). Flyway creates every table on the server's
-- first start, as blooddonor_migrator; blooddonor_runtime only ever reads and writes rows.

\set ON_ERROR_STOP on

\if :{?migrator_password}
\else
DO $$ BEGIN RAISE EXCEPTION 'Pass -v migrator_password=... -v runtime_password=...'; END $$;
\endif
\if :{?runtime_password}
\else
DO $$ BEGIN RAISE EXCEPTION 'Pass -v migrator_password=... -v runtime_password=...'; END $$;
\endif

-- Localhost only (takes effect at the next PostgreSQL restart; it is already the Ubuntu default).
ALTER SYSTEM SET listen_addresses = 'localhost';

-- Roles: the schema owner that runs migrations, and the server's own role.
SELECT 'CREATE ROLE blooddonor_migrator LOGIN'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'blooddonor_migrator') \gexec
SELECT 'CREATE ROLE blooddonor_runtime LOGIN'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'blooddonor_runtime') \gexec
ALTER ROLE blooddonor_migrator WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'migrator_password';
ALTER ROLE blooddonor_runtime WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'runtime_password';

SELECT 'CREATE DATABASE blooddonor OWNER blooddonor_migrator ENCODING ''UTF8'' TEMPLATE template0'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'blooddonor') \gexec
ALTER DATABASE blooddonor OWNER TO blooddonor_migrator;
REVOKE ALL ON DATABASE blooddonor FROM PUBLIC;
GRANT CONNECT ON DATABASE blooddonor TO blooddonor_runtime;

\connect blooddonor

-- Migrations use geography columns but never create extensions: that needs a superuser.
CREATE EXTENSION IF NOT EXISTS postgis;

ALTER SCHEMA public OWNER TO blooddonor_migrator;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO blooddonor_runtime;

-- Every table and sequence the migrator creates (all of them, through Flyway) is readable and
-- writable by the runtime role, and nothing more: it can't create, alter or drop anything.
ALTER DEFAULT PRIVILEGES FOR ROLE blooddonor_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO blooddonor_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE blooddonor_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO blooddonor_runtime;

-- Flyway's history table exists only after the first start: run this file again then (or the
-- REVOKE below by hand), so the runtime role can't change the migration history.
SELECT 'REVOKE INSERT, UPDATE, DELETE ON flyway_schema_history FROM blooddonor_runtime'
WHERE to_regclass('public.flyway_schema_history') IS NOT NULL \gexec
