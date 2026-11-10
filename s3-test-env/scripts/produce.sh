#!/usr/bin/env bash
# Writes a fresh batch of sensor readings into the bucket every few seconds,
# so a running S3 adapter finds something new on every poll.
set -euo pipefail

ENDPOINT="${S3_ENDPOINT:-http://seaweedfs:8333}"
BUCKET="${S3_BUCKET:-sp-test}"
INTERVAL="${INTERVAL_SECONDS:-5}"
STATUSES=(OK OK OK WARN ALARM)

echo "[produce] writing to s3://${BUCKET}/live/ on ${ENDPOINT} every ${INTERVAL}s"
while true; do
  now=$(date +%s%3N)
  {
    echo "["
    for i in 1 2 3; do
      t=$((180 + RANDOM % 100))
      p=$((10100 + RANDOM % 100))
      printf '  {"timestamp": %d, "sensorId": "sensor-0%d", "temperature": %d.%d, "pressure": %d.%d, "status": "%s"}' \
        "$now" "$i" $((t / 10)) $((t % 10)) $((p / 10)) $((p % 10)) "${STATUSES[RANDOM % 5]}"
      [ "$i" -lt 3 ] && echo "," || echo
    done
    echo "]"
  } > /tmp/batch.json
  aws --endpoint-url "$ENDPOINT" s3 cp /tmp/batch.json "s3://${BUCKET}/live/sensors-${now}.json" --only-show-errors
  echo "[produce] wrote live/sensors-${now}.json"
  sleep "$INTERVAL"
done