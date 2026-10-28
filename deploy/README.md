# Deploying the BloodDonor server

One Google Cloud VM runs everything: the Ktor server (Java 21), PostgreSQL 16 + PostGIS and Caddy,
which terminates HTTPS for a DuckDNS name. The server listens on `127.0.0.1:8080` only; Caddy is the
only way in from outside. Written for an **e2-micro, Ubuntu 24.04 LTS, 1 GB RAM**.

| File | Goes to | What it does |
|---|---|---|
| `setup-vm.sh` | run once (safe to re-run) | swap, security updates, ufw, fail2ban, Java, PostgreSQL + PostGIS, Caddy, user and directories; installs the files below |
| `postgresql-tuning.conf` | `/etc/postgresql/16/main/conf.d/` | memory settings for 1 GB, localhost only |
| `db-setup.sql` | run once with `psql` | database, `blooddonor_migrator` / `blooddonor_runtime` roles, PostGIS |
| `server.env.example` | `/etc/blooddonor/server.env` | every setting the server reads |
| `blooddonor.service` | `/etc/systemd/system/` | the server, hardened, low-memory JVM flags |
| `Caddyfile` | `/etc/caddy/Caddyfile` | HTTPS, security headers, 32 KB body limit, proxy to `127.0.0.1:8080` |
| `backup.sh`, `blooddonor-backup.service`, `.timer` | `/opt/blooddonor/bin/`, `/etc/systemd/system/` | nightly `pg_dump`, 7 days kept |
| `update.sh` | `/opt/blooddonor/bin/` | installs a new build, rolls back if `/health` fails |

Placeholders look like `<DOMAIN>` or `<runtime-password>`. No secret is ever stored in this repository.

## First-time setup

### 1. Google Cloud

- Create the VM: e2-micro, Ubuntu 24.04 LTS (x86/64), standard persistent disk (10+ GB).
  The always-free e2-micro exists only in `us-west1`, `us-central1` and `us-east1`.
- Reserve a **static external IP** and attach it, so the DNS name never goes stale.
- VPC firewall: allow TCP 80 and 443 to the VM (the *Allow HTTP/HTTPS traffic* checkboxes, i.e. the
  `http-server` / `https-server` tags). Port 22 is open by the default rules. Nothing else is needed:
  PostgreSQL and the server never listen on the external interface.
- Recommended: a snapshot schedule on the boot disk, so backups also exist outside the VM.

### 2. DuckDNS

Point your subdomain (e.g. `blooddonor-example.duckdns.org`) at the static IP. Caddy needs it to
resolve before it can get a certificate in step 8.

### 3. Prepare the VM

```bash
git clone https://github.com/Jadd017/blood-donor-app.git
cd blood-donor-app
sudo bash deploy/setup-vm.sh
```

### 4. Database

Generate the two passwords, create the roles and database, and keep the passwords in the shell for
step 5 (they are hex, so they need no quoting anywhere):

```bash
MIGRATOR_PW=$(openssl rand -hex 24)
RUNTIME_PW=$(openssl rand -hex 24)
sudo -u postgres psql -v migrator_password="$MIGRATOR_PW" -v runtime_password="$RUNTIME_PW" < deploy/db-setup.sql
```

### 5. Secrets

Copy the Firebase service account JSON to the VM (e.g. `gcloud compute scp`), then:

```bash
sudo install -m 600 -o blooddonor -g blooddonor ~/firebase-service-account.json /etc/blooddonor/firebase-service-account.json
shred -u ~/firebase-service-account.json

sudo install -m 600 -o blooddonor -g blooddonor deploy/server.env.example /etc/blooddonor/server.env
sudo sed -i "s/<runtime-password>/$RUNTIME_PW/; s/<migrator-password>/$MIGRATOR_PW/" /etc/blooddonor/server.env
sudo grep -c '<' /etc/blooddonor/server.env   # must print 0: no placeholders left
unset MIGRATOR_PW RUNTIME_PW
```

### 6. Build and start the server

Build on your own machine (JDK 21; no Android SDK needed), from the repository root:

```bash
./gradlew :server:distTar        # → server/build/distributions/server.tar
```

Copy `server.tar` to the VM, then:

```bash
sudo systemctl enable blooddonor
sudo /opt/blooddonor/bin/update.sh ~/server.tar
```

On the first start Flyway creates every table, the areas and the hospitals. `update.sh` waits up
to 90 s for `http://127.0.0.1:8080/health`; logs: `journalctl -u blooddonor -f`.

### 7. Lock the migration history

Now that Flyway's history table exists, take write access to it away from the runtime role:

```bash
sudo -u postgres psql -d blooddonor -c "REVOKE INSERT, UPDATE, DELETE ON flyway_schema_history FROM blooddonor_runtime;"
```

### 8. HTTPS

```bash
sed 's/<DOMAIN>/blooddonor-example.duckdns.org/' deploy/Caddyfile | sudo tee /etc/caddy/Caddyfile
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
journalctl -u caddy -n 20     # look for "certificate obtained successfully"
```

### 9. Backups

```bash
sudo systemctl enable --now blooddonor-backup.timer
sudo systemctl start blooddonor-backup        # one now, to check
sudo ls -l /var/backups/blooddonor
```

### 10. Check

```bash
curl https://blooddonor-example.duckdns.org/health              # OK
curl https://blooddonor-example.duckdns.org/api/v1/areas        # JSON list of areas
curl -i https://blooddonor-example.duckdns.org/api/v1/me        # 401 unauthorized
sudo ss -tlnp | grep -E ':8080|:5432'                           # 127.0.0.1 only
```

## Updates

Build a new `server.tar` (step 6), copy it over and run:

```bash
sudo /opt/blooddonor/bin/update.sh ~/server.tar
```

New migrations run automatically on start. The last three releases stay in
`/opt/blooddonor/releases/`; if the new one isn't healthy, `update.sh` switches back to the previous
one. A rollback can't undo migrations that already ran: restore a backup if that ever matters.

When the deploy kit itself changes, `git pull` and run `sudo bash deploy/setup-vm.sh` again, then
`sudo systemctl restart blooddonor`.

## Day-to-day

| Task | Command |
|---|---|
| Server logs | `journalctl -u blooddonor -f` |
| Restart | `sudo systemctl restart blooddonor` |
| Change a setting | edit `/etc/blooddonor/server.env`, then restart |
| Database shell | `sudo -u postgres psql -d blooddonor` |
| Restore a backup | `sudo -u postgres pg_restore --clean --if-exists -d blooddonor /var/backups/blooddonor/<file>.dump` |
| Banned SSH addresses | `sudo fail2ban-client status sshd` |
