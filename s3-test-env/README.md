<!--
  ~ Licensed to the Apache Software Foundation (ASF) under one or more
  ~ contributor license agreements.  See the NOTICE file distributed with
  ~ this work for additional information regarding copyright ownership.
  ~ The ASF licenses this file to You under the Apache License, Version 2.0
  ~ (the "License"); you may not use this file except in compliance with
  ~ the License.  You may obtain a copy of the License at
  ~
  ~    http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing, software
  ~ distributed under the License is distributed on an "AS IS" BASIS,
  ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  ~ See the License for the specific language governing permissions and
  ~ limitations under the License.
  ~
  -->

# S3 test environment

Local S3-compatible object storage for developing and testing StreamPipes S3
adapters. Two independent implementations — MinIO and SeaweedFS — are seeded
from the same `samples/` tree, so an adapter can be run against both and any
behaviour that differs is an S3 compatibility issue rather than a bug in the
adapter.

**Development only.** The credentials are throwaway, the endpoints speak plain
HTTP and there is no access control beyond a single all-powerful identity. Do
not run this anywhere but a developer machine.

## Quick start

The stack joins `spnet`, the network the StreamPipes stack uses. Bring
StreamPipes up first, or create the network yourself:

```bash
docker network create --driver bridge --subnet 172.31.0.0/16 spnet
```

Then:

```bash
docker compose up -d
docker compose logs -f seed      # watch the bucket being filled, then Ctrl-C
./scripts/verify.sh              # check every acceptance criterion
```

`docker compose up` in the foreground never returns, because `minio` and
`seaweedfs` are long-running. Use `-d` and tail the `seed` log as above.

Tear down, including the stored objects:

```bash
docker compose down -v
```

## Endpoints

| | MinIO | SeaweedFS |
| --- | --- | --- |
| S3 API, from the host | `http://localhost:9000` | `http://localhost:8333` |
| S3 API, from a container on `spnet` | `http://minio:9000` | `http://seaweedfs:8333` |
| Web console | `http://localhost:9001` | — |
| Health probe | `/minio/health/live` | `/healthz` |
| Access key | `streampipes` | `streampipes` |
| Secret key | `streampipes-secret` | `streampipes-secret` |
| Region | `us-east-1` | `us-east-1` |
| Bucket | `sp-test` | `sp-test` |

Use the **container** addresses when configuring an adapter. Inside the
StreamPipes extensions container `localhost` is that container itself, so
`http://localhost:9000` will not reach MinIO.

MinIO credentials come from the environment in `docker-compose.yml`; SeaweedFS
reads its own from `seaweedfs/s3.json`. Change one and you must change the
other.

The SeaweedFS S3 root answers `403` to anonymous callers — that is the
credentials being enforced, not a broken endpoint. Probe `/healthz` instead.

### A note on SeaweedFS volumes

The compose file pins `-volume.max=100 -master.volumeSizeLimitMB=64`, and that
is load-bearing. Left unset, the volume server sizes its volume limit from free
disk space; on a nearly-full disk it settles on a handful. Because **each S3
bucket is a SeaweedFS collection that needs its own volume**, a low limit lets
`CreateBucket` succeed and then fails the first `PutObject` with a bare
`HTTP 500` — which looks like a client or checksum problem and is not. The
volumes are sparse until written, so a high limit costs nothing.

Check the headroom any time bucket creation starts failing:

```bash
docker compose exec seaweedfs curl -s http://localhost:9333/dir/status
```

`Free: 0` in the `Topology` block is the symptom.

### A note on the MinIO image

Upstream `minio/minio` and `minio/mc` have been withdrawn from Docker Hub and
no longer pull anonymously from Docker Hub or quay.io. This stack therefore
pins `bitnamilegacy/minio:2025.7.23-debian-12-r5`, Bitnami's archived build of
MinIO 2025.7.23 — a real MinIO server with the web console, and `mc` on the
`PATH` inside the container. It is frozen: pinned, reproducible and never
updated.

SeaweedFS is actively maintained and is the endpoint to trust if the MinIO
image ever disappears too. If you need a second maintained implementation,
`localstack/localstack` is the usual drop-in (S3 on port 4566) and needs no
credentials configuration beyond `test`/`test`.

## Bucket contents

Every object below exists on **both** endpoints under `s3://sp-test/`.

| Key | Purpose |
| --- | --- |
| `json/sensors-2026-01-01.json` | Normal JSON array, schema A: `timestamp`, `sensorId`, `temperature`, `pressure`, `status` |
| `json/sensors-2026-01-02.json` | Normal, schema A, different day and values |
| `json/sensors-2026-01-03.json` | **Schema drift.** Matches the object pattern but carries schema B: `ts` (ISO-8601 string, not epoch millis), `sensor_id`, `temperatureCelsius`, `humidity`, `statusCode` (int, not string) |
| `csv/machine-data.csv` | Normal CSV with header: `timestamp,machineId,rpm,torque,vibration,state` |
| `broken/malformed.json` | **Invalid JSON** — unterminated array, trailing comma, unquoted key |
| `other/readme.txt` | Must **not** match the object pattern; documents the expectation in its own body |
| `large/machine-data-large.csv` | 100 MB CSV, ~2.24M rows. Not committed — see below |

### The object pattern

Tests configure the adapter with a pattern such as `json/sensors-*.json`.
Against the bucket above that gives three matches and four non-matches, which
is what makes the pattern filter observable:

```
matches       json/sensors-2026-01-01.json
              json/sensors-2026-01-02.json
              json/sensors-2026-01-03.json

no match      csv/machine-data.csv
              broken/malformed.json
              other/readme.txt
              large/machine-data-large.csv
```

If `other/readme.txt` ever appears in an adapter's output, the filter is broken.

### The large sample

The 100 MB CSV is generated on demand rather than committed, and is gitignored:

```bash
./scripts/generate-large-csv.sh          # 100 MB, the default
./scripts/generate-large-csv.sh 250      # or any size in MB
docker compose run --rm seed             # upload it to both endpoints
```

It uploads as a multipart upload, which is worth keeping in mind when it is the
object under test.

## Re-seeding

`scripts/seed.sh` is idempotent — it creates the bucket if missing and uploads
`samples/` over whatever is there. Run it again after editing or adding a
sample:

```bash
docker compose run --rm seed
```

To start from a genuinely empty bucket, `docker compose down -v` first; the
objects live in the `minio` and `seaweedfs` named volumes.

## Working with the bucket by hand

No host-side AWS CLI or `mc` is required — both run fine in a container.

List a bucket (swap the endpoint for `http://localhost:9000` to hit MinIO):

```bash
docker run --rm --network host \
  -e AWS_ACCESS_KEY_ID=streampipes \
  -e AWS_SECRET_ACCESS_KEY=streampipes-secret \
  -e AWS_DEFAULT_REGION=us-east-1 \
  amazon/aws-cli:2.37.10 \
  --endpoint-url http://localhost:8333 s3 ls s3://sp-test/ --recursive --human-readable
```

Or use the `mc` that ships inside the MinIO container:

```bash
docker compose exec minio mc ls --recursive local/sp-test
```

## Files

```
s3-test-env/
├── docker-compose.yml              minio, seaweedfs, seed
├── seaweedfs/s3.json               SeaweedFS S3 identity and credentials
├── samples/                        uploaded verbatim to s3://sp-test/
│   ├── json/                       schema A x2, schema B x1
│   ├── csv/machine-data.csv
│   ├── broken/malformed.json
│   ├── other/readme.txt
│   └── large/                      generated, gitignored
└── scripts/
    ├── seed.sh                     runs in the seed container, not on the host
    ├── generate-large-csv.sh       host script, writes samples/large/
    └── verify.sh                   host script, checks the acceptance criteria
```

## What `verify.sh` checks

1. Both endpoints answer on their published host ports and `s3://sp-test` is
   listable with the test credentials.
2. Every expected sample object is present on both. The large sample is
   reported as skipped rather than failed when it has not been generated.
3. The endpoints are reachable **by service name** from the StreamPipes
   extensions container. When no such container is on `spnet`, it falls back to
   proving in-network DNS and HTTP work at all — which is the thing the
   extensions container depends on.

It exits non-zero on the number of failures, so it is usable as a gate in a
larger script.
