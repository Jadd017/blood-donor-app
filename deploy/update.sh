#!/usr/bin/env bash
# Installs a server build (server.tar from `./gradlew :server:distTar`) as a new release,
# restarts the service and waits for /health. If it doesn't come up, the previous release is
# put back. Installed by setup-vm.sh as /opt/blooddonor/bin/update.sh.
#
# Usage: sudo /opt/blooddonor/bin/update.sh ~/server.tar
#
# Note: Flyway migrations only go forward. A rollback after a release whose migrations already
# ran puts the old code on the new schema; restore a backup if that ever matters.
set -euo pipefail

BASE=/opt/blooddonor
KEEP_RELEASES=3

if [[ $EUID -ne 0 ]]; then
    echo "Run as root: sudo $0 <server.tar>" >&2
    exit 1
fi
tarball="${1:?Usage: $0 <server.tar>}"
[[ -f "$tarball" ]] || { echo "No such file: $tarball" >&2; exit 1; }
tar -tf "$tarball" server/bin/server >/dev/null 2>&1 \
    || { echo "$tarball is not a server distribution (expected server/bin/server inside)." >&2; exit 1; }

port="$(sed -n 's/^PORT=\([0-9]\+\).*/\1/p' /etc/blooddonor/server.env 2>/dev/null | tail -n 1)"
health="http://127.0.0.1:${port:-8080}/health"

release="$BASE/releases/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$release"
tar -xf "$tarball" -C "$release" --strip-components=1 --no-same-owner
chown -R root:root "$release"
chmod -R u=rwX,go=rX "$release"
chmod 755 "$release/bin/server"

previous="$(readlink -f "$BASE/current" 2>/dev/null || true)"

switch_to() {
    ln -sfn "$1" "$BASE/current.new"
    mv -T "$BASE/current.new" "$BASE/current"
}

wait_healthy() {
    # Start-up on an e2-micro (JVM, Flyway, Firebase) can take a while.
    for _ in $(seq 1 90); do
        if curl -fsS --max-time 2 "$health" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    return 1
}

echo "Switching to $release"
switch_to "$release"
systemctl restart blooddonor

if wait_healthy; then
    echo "Healthy: $health answers OK."
else
    echo "The new release did not become healthy. Last log lines:" >&2
    journalctl -u blooddonor -n 30 --no-pager >&2 || true
    if [[ -n "$previous" && -d "$previous" && "$previous" != "$release" ]]; then
        echo "Rolling back to $previous" >&2
        switch_to "$previous"
        systemctl restart blooddonor
        wait_healthy && echo "Previous release is back up." >&2 || echo "Previous release is not healthy either." >&2
    fi
    exit 1
fi

# Keep the newest few releases (never the one running).
current="$(readlink -f "$BASE/current")"
ls -1dt "$BASE"/releases/*/ 2>/dev/null | sed 's:/$::' | tail -n +$((KEEP_RELEASES + 1)) | while read -r old; do
    if [[ "$old" != "$current" ]]; then rm -rf -- "$old"; fi
done
echo "Done."
