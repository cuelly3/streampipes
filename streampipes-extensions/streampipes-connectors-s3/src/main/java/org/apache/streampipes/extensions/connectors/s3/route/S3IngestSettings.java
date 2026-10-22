/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.streampipes.extensions.connectors.s3.route;

import org.apache.streampipes.extensions.connectors.s3.client.S3ConnectionSettings;

/**
 * Everything the ingest route needs, as plain values.
 *
 * @param connection    endpoint, credentials and source bucket
 * @param pattern       glob applied to the object key
 * @param afterRead     what to do with an object once it has been read
 * @param archiveBucket destination for {@link AfterReadAction#MOVE}, else null
 * @param pollDelayMs   delay between polls
 * @param maxPerPoll    objects per poll
 */
public record S3IngestSettings(
    S3ConnectionSettings connection,
    ObjectKeyPattern pattern,
    AfterReadAction afterRead,
    String archiveBucket,
    long pollDelayMs,
    int maxPerPoll
) {

  public S3IngestSettings {
    if (afterRead == AfterReadAction.MOVE && (archiveBucket == null || archiveBucket.isBlank())) {
      throw new IllegalArgumentException("A destination bucket is required when moving objects");
    }
  }
}
