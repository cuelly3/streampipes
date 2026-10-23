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
# Creates the test bucket on every configured S3 endpoint and uploads
# ./samples into it. Runs inside the `seed` service of docker-compose.yml;
# it is not meant to be run on the host.
#
# Environment:
#   S3_BUCKET      bucket to create (default: sp-test)
#   S3_ENDPOINTS   space-separated "name=url" pairs
#   SAMPLES_DIR    directory to upload (default: /samples)

set -euo pipefail

BUCKET="${S3_BUCKET:-sp-test}"
SAMPLES_DIR="${SAMPLES_DIR:-/samples}"
ENDPOINTS="${S3_ENDPOINTS:?S3_ENDPOINTS must be set, e.g. 'minio=http://minio:9000'}"

log() {
  printf '[seed] %s\n' "$*"
}

seed_endpoint() {
  local name="$1" url="$2"
  log "=== ${name} (${url}) ==="

  # CreateBucket is not idempotent in S3; an existing bucket we own is fine.
  if aws --endpoint-url "$url" s3api head-bucket --bucket "$BUCKET" >/dev/null 2>&1; then
    log "bucket s3://${BUCKET} already exists"
  else
    log "creating bucket s3://${BUCKET}"
    aws --endpoint-url "$url" s3 mb "s3://${BUCKET}"
  fi

  log "uploading ${SAMPLES_DIR} -> s3://${BUCKET}/"
  # --exclude keeps the .gitkeep placeholder of samples/large out of the bucket.
  aws --endpoint-url "$url" s3 cp "$SAMPLES_DIR/" "s3://${BUCKET}/" \
    --recursive \
    --no-progress \
    --exclude "*/.gitkeep" \
    --exclude ".gitkeep"

  log "objects now in s3://${BUCKET}:"
  aws --endpoint-url "$url" s3 ls "s3://${BUCKET}/" --recursive --human-readable
}

for pair in $ENDPOINTS; do
  seed_endpoint "${pair%%=*}" "${pair#*=}"
done

log "done - all endpoints seeded"
