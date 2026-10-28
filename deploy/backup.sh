#!/usr/bin/env bash
# Nightly dump of the blooddonor database (custom format, restore with pg_restore), keeping 7 days.
# Run by blooddonor-backup.timer as the postgres user; installed as /opt/blooddonor/bin/backup.sh.
#
# Restore into an empty database:
#   sudo -u postgres pg_restore --clean --if-exists -d blooddonor /var/backups/blooddonor/<file>.dump
set -euo pipefail

DIR=/var/backups/blooddonor
KEEP_DAYS=7
umask 077

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
tmp="$DIR/.blooddonor-$stamp.dump.partial"
trap 'rm -f "$tmp"' EXIT

pg_dump --format=custom --compress=6 --dbname=blooddonor --file="$tmp"
mv "$tmp" "$DIR/blooddonor-$stamp.dump"

# Older than KEEP_DAYS: delete. A failed dump above never gets here, so old dumps stay.
find "$DIR" -maxdepth 1 -name 'blooddonor-*.dump' -type f -mtime +$((KEEP_DAYS - 1)) -delete

echo "Backup written: $DIR/blooddonor-$stamp.dump ($(du -h "$DIR/blooddonor-$stamp.dump" | cut -f1))"
