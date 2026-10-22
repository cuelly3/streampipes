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

public enum AfterReadAction {

  KEEP_IN_MEMORY("keep-in-memory"),

  KEEP_PERSISTENT("keep-persistent"),

  MOVE("move"),

  DELETE("delete");

  private final String internalId;

  AfterReadAction(String internalId) {
    this.internalId = internalId;
  }

  public String internalId() {
    return internalId;
  }

  public boolean isReadOnly() {
    return this == KEEP_IN_MEMORY || this == KEEP_PERSISTENT;
  }

  public static AfterReadAction fromInternalId(String id) {
    for (var action : values()) {
      if (action.internalId.equals(id)) {
        return action;
      }
    }
    throw new IllegalArgumentException("Unknown after-read action: " + id);
  }
}
