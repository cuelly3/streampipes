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
# Checks the acceptance criteria of the test environment:
#   1. both S3 endpoints answer on the published host ports
#   2. the sp-test bucket is listable and holds the expected sample objects
#   3. the endpoints are reachable from inside the StreamPipes extensions
#      container by service name (skipped if that container is not running)
#
# Usage: ./scripts/verify.sh

set -uo pipefail

AWS_IMAGE="amazon/aws-cli:2.37.10"
BUCKET="sp-test"
NETWORK="spnet"
EXPECTED_KEYS=(
  "broken/malformed.json"
  "csv/machine-data.csv"
  "json/sensors-2026-01-01.json"
  "json/sensors-2026-01-02.json"
  "json/sensors-2026-01-03.json"
  "other/readme.txt"
)

failures=0

pass() { printf '  \033[32mPASS\033[0m %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$*"; failures=$((failures + 1)); }
skip() { printf '  \033[33mSKIP\033[0m %s\n' "$*"; }

# Runs the AWS CLI on the host, reaching the endpoint through its published port.
aws_host() {
  local endpoint="$1"
  shift
  docker run --rm \
    --network host \
    -e AWS_ACCESS_KEY_ID=streampipes \
    -e AWS_SECRET_ACCESS_KEY=streampipes-secret \
    -e AWS_DEFAULT_REGION=us-east-1 \
    -e AWS_EC2_METADATA_DISABLED=true \
    "$AWS_IMAGE" --endpoint-url "$endpoint" "$@"
}

check_endpoint() {
  local name="$1" host_url="$2"
  echo
  echo "== ${name} on ${host_url} =="

  local listing
  if ! listing=$(aws_host "$host_url" s3 ls "s3://${BUCKET}/" --recursive 2>&1); then
    fail "cannot list s3://${BUCKET} (${listing##*$'\n'})"
    return
  fi
  pass "s3://${BUCKET} is listable"

  local key
  for key in "${EXPECTED_KEYS[@]}"; do
    if grep -qF " ${key}" <<<"$listing"; then
      pass "object ${key}"
    else
      fail "object ${key} is missing"
    fi
  done

  if grep -qF "large/machine-data-large.csv" <<<"$listing"; then
    pass "large sample present"
  else
    skip "large sample absent (run scripts/generate-large-csv.sh, then re-seed)"
  fi
}

echo "### 1+2. endpoints and bucket contents (from the host)"
check_endpoint "MinIO" "http://localhost:9000"
check_endpoint "SeaweedFS" "http://localhost:8333"

echo
echo "### 3. reachability from the StreamPipes extensions container"
sp_container=$(docker ps --filter "network=${NETWORK}" --format '{{.Names}}' \
  | grep -i "extensions" | head -1)

if [ -z "$sp_container" ]; then
  skip "no extensions container on network '${NETWORK}' - start the StreamPipes stack to check this"
  # Fall back to proving in-network DNS works at all, which is what the
  # extensions container will rely on.
  for target in "http://minio:9000/minio/health/live" "http://seaweedfs:8333/healthz"; do
    if docker run --rm --network "$NETWORK" --entrypoint curl \
        "chrislusf/seaweedfs:4.48" -fsS -o /dev/null --max-time 10 "$target" 2>/dev/null; then
      pass "in-network DNS + HTTP reaches ${target}"
    else
      fail "in-network DNS + HTTP cannot reach ${target}"
    fi
  done
else
  pass "found extensions container '${sp_container}'"
  for target in "http://minio:9000/minio/health/live" "http://seaweedfs:8333/healthz"; do
    if docker exec "$sp_container" sh -c \
        "curl -fsS -o /dev/null --max-time 10 '${target}' 2>/dev/null \
         || wget -q -O /dev/null -T 10 '${target}' 2>/dev/null"; then
      pass "${sp_container} reaches ${target}"
    else
      fail "${sp_container} cannot reach ${target}"
    fi
  done
fi

echo
if [ "$failures" -eq 0 ]; then
  echo "All checks passed."
else
  echo "${failures} check(s) failed."
fi
exit "$failures"
