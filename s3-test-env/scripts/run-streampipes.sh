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
# Brings up a StreamPipes stack with the locally built S3 adapter, so the
# adapter can be used from the UI against the MinIO and SeaweedFS endpoints in
# ../docker-compose.yml.
#
# Core and UI come from the published images. The extensions service is the one
# piece built from this checkout: it runs in a stock JRE container with the
# locally built jar bind-mounted, so a rebuild is a Maven command and never a
# Docker build.
#
# It has to run inside the Docker network, not on the host: the broker address
# an adapter publishes to comes from the grounding that core assigns, which uses
# the container name 'nats'. A host JVM cannot resolve that, and objects would
# be polled and parsed correctly and then silently fail to publish.
#
#   ./run-streampipes.sh up      start everything (default)
#   ./run-streampipes.sh down    stop and remove everything it started
#   ./run-streampipes.sh logs    tail the extensions service log
#
# On a fresh clone this is the only command needed: it creates the Docker
# network, starts the object storage, builds the extensions jar if it is not
# there yet, and brings up core, the UI and the extensions service.
#
# Requires: Docker with Compose, a JDK 25, Maven, curl and python3.
#
# Development only: the credentials below are throwaway.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
STATE_DIR="${TMPDIR:-/tmp}/streampipes-s3-demo"

SP_VERSION="0.99.0-SNAPSHOT"
NETWORK="spnet"
ADMIN_EMAIL="admin@streampipes.apache.org"
ADMIN_PASSWORD="streampipes-demo-admin"
UI_PORT=8088
CORE_PORT=8030
EXT_PORT=8090
JRE_IMAGE="eclipse-temurin:25-jre-alpine"
ADAPTER_APP_ID="org.apache.streampipes.connectors.s3.object"

EXT_JAR="$REPO_ROOT/streampipes-extensions/streampipes-extensions-all-iiot/target/streampipes-extensions-all-iiot.jar"

log() { printf '[sp] %s\n' "$*"; }

java_home() {
  if [ -n "${JAVA_HOME:-}" ]; then echo "$JAVA_HOME"; return; fi
  for candidate in /usr/lib/jvm/java-25-openjdk-amd64 /usr/lib/jvm/java-1.25.0-openjdk-amd64; do
    [ -x "$candidate/bin/java" ] && { echo "$candidate"; return; }
  done
  echo ""
}

down() {
  # Older revisions of this script ran the extensions service on the host; kill
  # any leftover, otherwise it holds port 8090 and the container cannot bind it.
  pkill -f "streampipes-extensions-all-iiot.jar" 2>/dev/null || true
  log "removing containers"
  docker rm -f sp-extensions sp-ui sp-backend sp-nats sp-couchdb >/dev/null 2>&1 || true
  rm -rf "$STATE_DIR"
  if [ "${1:-}" = "--all" ]; then
    log "stopping the object storage too"
    ( cd "$REPO_ROOT/s3-test-env" && docker compose down -v >/dev/null 2>&1 ) || true
  else
    log "the MinIO/SeaweedFS stack is left running; use 'down --all' to stop it too"
  fi
  log "done"
}

up() {
  local jh
  jh="$(java_home)"
  [ -n "$jh" ] || { echo "No JDK 25 found; set JAVA_HOME" >&2; exit 1; }

  if [ ! -f "$EXT_JAR" ]; then
    log "extensions jar not built yet - building it now (this takes a while on a fresh clone)"
    ( cd "$REPO_ROOT" && JAVA_HOME="$jh" mvn -q \
        -pl streampipes-extensions/streampipes-extensions-all-iiot -am \
        -DskipTests -Dmaven.javadoc.skip=true package )
    [ -f "$EXT_JAR" ] || { echo "Build finished but $EXT_JAR is missing" >&2; exit 1; }
  fi

  # The object storage compose file joins this network as external, so it has
  # to exist before that compose project starts.
  if ! docker network inspect "$NETWORK" >/dev/null 2>&1; then
    log "creating the '$NETWORK' docker network"
    docker network create --driver bridge --subnet 172.31.0.0/16 "$NETWORK" >/dev/null
  fi

  if ! curl -s -o /dev/null --max-time 3 http://localhost:9000/minio/health/live; then
    log "starting MinIO and SeaweedFS"
    ( cd "$REPO_ROOT/s3-test-env" && docker compose up -d >/dev/null )
    for _ in $(seq 1 60); do
      if (cd "$REPO_ROOT/s3-test-env" && docker compose ps -a --format '{{.Service}}:{{.Status}}') \
         | grep -q "seed:Exited (0)"; then
        break
      fi
      sleep 3
    done
    log "object storage ready and seeded"
  else
    log "object storage already running"
  fi

  # The extensions service runs on the host, so the backend container reaches
  # it through the bridge gateway rather than a container name.
  local gateway
  gateway="$(docker network inspect "$NETWORK" --format '{{range .IPAM.Config}}{{.Gateway}}{{end}}')"

  mkdir -p "$STATE_DIR"
  local secret
  secret="$(head -c 48 /dev/urandom | base64 | tr -d '\n=' | head -c 44)"
  echo "$secret" > "$STATE_DIR/secret"

  docker rm -f sp-extensions sp-ui sp-backend sp-nats sp-couchdb >/dev/null 2>&1 || true

  log "starting couchdb and nats"
  docker run -d --name sp-couchdb --network "$NETWORK" --network-alias couchdb \
    -e COUCHDB_USER=admin -e COUCHDB_PASSWORD=admin couchdb:3.3.1 >/dev/null
  docker run -d --name sp-nats --network "$NETWORK" --network-alias nats nats >/dev/null

  log "starting backend"
  docker run -d --name sp-backend --network "$NETWORK" --network-alias backend \
    -p "$CORE_PORT:8030" \
    -e SP_INITIAL_SERVICE_USER_SECRET="$secret" \
    -e SP_CLIENT_SECRET="$secret" \
    -e SP_PRIORITIZED_PROTOCOL=nats \
    -e SP_INITIAL_ADMIN_EMAIL="$ADMIN_EMAIL" \
    -e SP_INITIAL_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
    "apachestreampipes/backend:$SP_VERSION" >/dev/null

  log "starting ui"
  docker run -d --name sp-ui --network "$NETWORK" --network-alias ui \
    -p "$UI_PORT:8088" \
    -e "SP_HTTP_SERVER_ADAPTER_ENDPOINT=$gateway:$EXT_PORT" \
    "apachestreampipes/ui:$SP_VERSION" >/dev/null

  log "waiting for the backend to finish its initial setup"
  for _ in $(seq 1 90); do
    if docker logs sp-backend 2>&1 | grep -q "Auto-Setup finished"; then break; fi
    sleep 5
  done

  log "starting the extensions service (JRE container, jar bind-mounted)"
  docker run -d --name sp-extensions --network "$NETWORK" --network-alias extensions \
    -p "$EXT_PORT:8090" \
    -v "$EXT_JAR:/app.jar:ro" \
    -e SP_CLIENT_SECRET="$secret" \
    -e SP_CORE_HOST=backend -e SP_CORE_PORT=8030 \
    -e SP_HOST=extensions -e SP_PORT=8090 \
    -e SP_PRIORITIZED_PROTOCOL=nats \
    "$JRE_IMAGE" java -jar /app.jar

  for _ in $(seq 1 90); do
    if curl -s -o /dev/null --max-time 2 "http://localhost:$EXT_PORT/api/v1/worker/adapters/$ADAPTER_APP_ID/assets"; then
      break
    fi
    sleep 3
  done
  docker logs sp-extensions > "$STATE_DIR/extensions.log" 2>&1 || true

  install_adapter
  summary
}

install_adapter() {
  local base="http://localhost:$CORE_PORT/streampipes-backend"
  local token
  token="$(curl -s --max-time 15 -X POST "$base/api/v2/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" \
    | python3 -c 'import sys,json; print(json.load(sys.stdin).get("accessToken",""))')"

  if [ -z "$token" ]; then
    log "WARNING: could not log in to install the adapter; do it from the UI instead"
    return
  fi

  curl -s --max-time 20 -H "Authorization: Bearer $token" \
    "$base/api/v2/extension-items" -o "$STATE_DIR/items.json"

  python3 - "$STATE_DIR/items.json" "$STATE_DIR/install.json" "$ADAPTER_APP_ID" <<'PY'
import json, sys
items = json.load(open(sys.argv[1]))
match = [i for i in items if i.get("appId") == sys.argv[3]]
if not match:
    raise SystemExit("S3 adapter not offered by core - is the extensions service registered?")
item = match[0]
json.dump({
    "appId": item["appId"],
    "serviceTagPrefix": item.get("serviceTagPrefix"),
    "elementId": item.get("elementId"),
    "installed": False,
}, open(sys.argv[2], "w"))
PY

  curl -s --max-time 30 -X POST "$base/api/v2/extension-installation" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    -d @"$STATE_DIR/install.json" >/dev/null
  log "S3 adapter installed"
}

summary() {
  cat <<EOF

  StreamPipes UI   http://localhost:$UI_PORT
  Login            $ADMIN_EMAIL / $ADMIN_PASSWORD

  In the UI: Connect -> New adapter -> S3 Object Storage, then configure.
  The extensions service runs inside the Docker network, so the endpoint is the
  container name - not localhost, which would be that container itself.
  For the JSON samples pick format Json -> Array: they are JSON arrays.

    Endpoint URL    http://minio:9000          (or http://seaweedfs:8333)
    Region          us-east-1
    Access key      streampipes
    Secret key      streampipes-secret
    Bucket          sp-test
    Object pattern  json/sensors-*.json

  Extensions log   docker logs -f sp-extensions
  Tear down        $0 down

EOF
}

case "${1:-up}" in
  up) up ;;
  down) shift || true; down "${1:-}" ;;
  logs) docker logs -f sp-extensions ;;
  *) echo "usage: $0 [up|down|logs]" >&2; exit 1 ;;
esac
