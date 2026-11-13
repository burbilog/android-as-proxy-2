#!/bin/sh
# P0 soak monitor: run on marduk. Poll real HTTPS through Zebra's SOCKS from aap2-test.
# No Android changes, no sshd changes, no log parsing, no server-side daemon.
set -u

interval=${1:-300}
case "$interval" in
    ''|*[!0-9]*) echo "Usage: sh scripts/soak-monitor.sh [seconds >= 30]" >&2; exit 2 ;;
esac
if [ "$interval" -lt 30 ]; then
    echo "Interval must be at least 30 seconds" >&2
    exit 2
fi

printf 'utc,curl_exit,http_code,seconds\n'
while :; do
    stamp=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
    result=$(incus exec aap2-test -- curl -sS --max-time 25 \
        --proxy socks5h://127.0.0.1:10909 \
        -o /dev/null -w '%{http_code},%{time_total}' \
        https://example.org/ 2>/dev/null)
    rc=$?
    if [ -z "$result" ]; then result='000,0'; fi
    printf '%s,%d,%s\n' "$stamp" "$rc" "$result"
    sleep "$interval" || exit 0
done
