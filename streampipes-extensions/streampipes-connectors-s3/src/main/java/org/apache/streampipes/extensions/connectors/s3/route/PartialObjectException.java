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


public class PartialObjectException extends Exception {

  private static final long serialVersionUID = 1L;

  private final String objectKey;
  private final long eventsEmitted;

  public PartialObjectException(String objectKey, long eventsEmitted, Throwable cause) {
    super("Failed while parsing '%s' after emitting %d event(s): %s"
        .formatted(objectKey, eventsEmitted, cause.getMessage()), cause);
    this.objectKey = objectKey;
    this.eventsEmitted = eventsEmitted;
  }

  public String objectKey() {
    return objectKey;
  }

  public long eventsEmitted() {
    return eventsEmitted;
  }
}
