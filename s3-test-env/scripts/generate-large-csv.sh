#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Generates the large CSV sample used by the throughput and memory tests. The
# file is deliberately not checked in - run this once, then re-run `seed.sh`
# (docker compose up seed) to upload it.
#
# Usage: ./scripts/generate-large-csv.sh [size-in-mb]   (default: 100)

set -euo pipefail

SIZE_MB="${1:-100}"
OUT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/samples/large"
OUT_FILE="${OUT_DIR}/machine-data-large.csv"

mkdir -p "$OUT_DIR"

echo "[large] generating ~${SIZE_MB} MB at ${OUT_FILE}"

python3 - "$OUT_FILE" "$SIZE_MB" <<'PY'
import sys

out_file, size_mb = sys.argv[1], int(sys.argv[2])
target = size_mb * 1024 * 1024
header = "timestamp,machineId,rpm,torque,vibration,state\n"
machines = ("machine-a", "machine-b", "machine-c", "machine-d")
states = ("RUNNING", "RUNNING", "RUNNING", "IDLE", "FAULT")

written = 0
row = 0
base_ts = 1767225600000

with open(out_file, "w", encoding="utf-8", newline="") as handle:
    handle.write(header)
    written += len(header)
    chunk = []
    while written < target:
        row += 1
        line = "%d,%s,%d,%.1f,%.2f,%s\n" % (
            base_ts + row * 100,
            machines[row % len(machines)],
            900 + (row * 7) % 900,
            80.0 + (row * 13) % 1400 / 10.0,
            (row * 17) % 150 / 100.0,
            states[row % len(states)],
        )
        chunk.append(line)
        written += len(line)
        if len(chunk) >= 50_000:
            handle.writelines(chunk)
            chunk = []
    if chunk:
        handle.writelines(chunk)

print("[large] %d data rows, %.1f MiB" % (row, written / 1024 / 1024))
PY

ls -lh "$OUT_FILE"
echo "[large] now upload it:  docker compose run --rm seed"
