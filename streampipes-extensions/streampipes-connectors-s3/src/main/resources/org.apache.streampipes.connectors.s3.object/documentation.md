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

## S3 Object Storage

## Description

Reads objects from S3 and S3-compatible object storage such as MinIO or
SeaweedFS, and turns each object into events using the selected parser.

Objects are streamed rather than buffered, so large objects do not have to fit
in memory.

***

## Configuration

### Endpoint URL

Base URL of the S3 API, for example `https://s3.eu-central-1.amazonaws.com` for
AWS or `http://minio:9000` for a MinIO deployment. When StreamPipes runs in a
container, this must be an address reachable from inside that container —
`localhost` refers to the extensions container itself.

### Region

The region the bucket belongs to, for example `eu-central-1`. S3-compatible
stores generally accept `us-east-1`.

### Access key / Secret key

Credentials used to authenticate against the endpoint. The secret key is stored
encrypted.

### Bucket

Name of the bucket to read objects from. The adapter never creates a bucket.

### Object pattern

A glob matched against the object key, for example `json/sensors-*.json`. A
single `*` stays within one key segment, `**` crosses `/`, and `?` matches one
character. The leading literal part of the pattern is pushed down to the server
as a listing prefix; the rest is applied by the adapter.

### After read

What happens to an object once it has been read.

| Option | Behaviour | Survives a restart? | Extra permissions |
| --- | --- | --- | --- |
| Keep (persistent) | Bucket untouched; processed objects tracked on disk | Only if the state directory is on a volume | none |
| Keep (in memory) | Bucket untouched; tracking lost on restart | No - everything is ingested again | none |
| Move | Object copied to the archive bucket, then removed | Yes | `PutObject`, `DeleteObject` |
| Delete | Object deleted after a successful read | Yes | `DeleteObject` |

Objects are identified by key plus ETag, so re-uploading different content under
an existing key ingests it again.

### Archive bucket

Destination for the move action; ignored otherwise.

### Poll interval

How often the bucket is listed for new objects.
