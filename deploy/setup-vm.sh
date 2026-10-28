#!/usr/bin/env bash
# One-time (and safe to re-run) preparation of a fresh Ubuntu 24.04 VM for the BloodDonor server:
# swap, automatic security updates, firewall, fail2ban, Java 21, PostgreSQL 16 + PostGIS, Caddy,
# the "blooddonor" system user and its directories, and the static files of this deploy kit.
# It starts nothing that needs secrets: the app service is enabled later (see deploy/README.md).
#
# Usage, from a checkout of the repository on the VM:  sudo bash deploy/setup-vm.sh
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
    echo "Run as root: sudo bash $0" >&2
    exit 1
fi
. /etc/os-release
if [[ "${ID:-}" != "ubuntu" || "${VERSION_ID:-}" != "24.04" ]]; then
    echo "Warning: written for Ubuntu 24.04, this is ${PRETTY_NAME:-unknown}." >&2
fi

KIT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export DEBIAN_FRONTEND=noninteractive

step() { echo; echo "==> $*"; }

# --- Swap: 1 GB RAM is tight for a JVM next to PostgreSQL -------------------------
step "Swap (2 GB, swappiness 10)"
if [[ ! -f /swapfile ]]; then
    fallocate -l 2G /swapfile
    chmod 600 /swapfile
    mkswap /swapfile
fi
swapon --show=NAME --noheadings | grep -qx /swapfile || swapon /swapfile
grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
echo 'vm.swappiness=10' > /etc/sysctl.d/99-blooddonor.conf
sysctl -q -p /etc/sysctl.d/99-blooddonor.conf

# --- Packages ---------------------------------------------------------------------
step "Base packages"
apt-get update -q
apt-get install -y -q ca-certificates curl gnupg debian-keyring debian-archive-keyring apt-transport-https \
    unattended-upgrades ufw fail2ban python3-systemd openjdk-21-jre-headless \
    postgresql-16 postgresql-16-postgis-3

step "Caddy (official apt repository)"
if [[ ! -f /usr/share/keyrings/caddy-stable-archive-keyring.gpg ]]; then
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
        | gpg --batch --yes --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
    chmod o+r /usr/share/keyrings/caddy-stable-archive-keyring.gpg
fi
if [[ ! -f /etc/apt/sources.list.d/caddy-stable.list ]]; then
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
        > /etc/apt/sources.list.d/caddy-stable.list
    chmod o+r /etc/apt/sources.list.d/caddy-stable.list
    apt-get update -q
fi
apt-get install -y -q caddy

# --- Automatic security updates ---------------------------------------------------
step "Unattended upgrades"
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
EOF
systemctl enable --now unattended-upgrades

# --- Firewall: SSH, HTTP (ACME challenge + redirect) and HTTPS only ---------------
step "ufw (22, 80, 443)"
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

step "fail2ban (SSH)"
cat > /etc/fail2ban/jail.d/sshd.local <<'EOF'
[sshd]
enabled = true
backend = systemd
maxretry = 5
findtime = 10m
bantime = 1h
EOF
systemctl enable fail2ban
systemctl restart fail2ban

# --- PostgreSQL: small-VM tuning, localhost only ----------------------------------
step "PostgreSQL tuning"
install -m 644 -o root -g root "$KIT/postgresql-tuning.conf" /etc/postgresql/16/main/conf.d/blooddonor-tuning.conf
systemctl enable postgresql
systemctl restart postgresql

# --- App user and directories -----------------------------------------------------
step "User blooddonor and directories"
if ! id blooddonor &>/dev/null; then
    useradd --system --home-dir /opt/blooddonor --no-create-home --shell /usr/sbin/nologin blooddonor
fi
install -d -m 755 -o root -g root /opt/blooddonor /opt/blooddonor/releases /opt/blooddonor/bin
install -d -m 700 -o blooddonor -g blooddonor /etc/blooddonor
# Dumps are made by the postgres user (backup.sh); nobody else needs to read them.
install -d -m 700 -o postgres -g postgres /var/backups/blooddonor

# --- Static files of the deploy kit -----------------------------------------------
step "Deploy kit files"
install -m 755 -o root -g root "$KIT/update.sh" /opt/blooddonor/bin/update.sh
install -m 755 -o root -g root "$KIT/backup.sh" /opt/blooddonor/bin/backup.sh
install -m 644 -o root -g root "$KIT/blooddonor.service" /etc/systemd/system/blooddonor.service
install -m 644 -o root -g root "$KIT/blooddonor-backup.service" /etc/systemd/system/blooddonor-backup.service
install -m 644 -o root -g root "$KIT/blooddonor-backup.timer" /etc/systemd/system/blooddonor-backup.timer
systemctl daemon-reload

echo
echo "Done. Next: deploy/README.md, step 3 (database)."
