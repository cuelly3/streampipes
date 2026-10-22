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

import java.io.InputStream;

/**
 * What the route does with each matching object. Keeping this a plain callback
 * is what keeps {@link S3IngestRoute} free of StreamPipes types, so the route
 * stays reusable for other Camel-based connectors.
 */
@FunctionalInterface
public interface S3ObjectHandler {

  /**
   * @param objectKey the S3 key, for logging and error messages
   * @param body      the object's content; the route closes it afterwards
   */
  void handle(String objectKey, InputStream body) throws Exception;
}
