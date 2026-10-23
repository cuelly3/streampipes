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

import java.util.Objects;
import java.util.regex.Pattern;


public final class ObjectKeyPattern {

  private static final String WILDCARDS = "*?";

  private final String pattern;
  private final String literalPrefix;
  private final Pattern regex;

  private ObjectKeyPattern(String pattern, String literalPrefix, Pattern regex) {
    this.pattern = pattern;
    this.literalPrefix = literalPrefix;
    this.regex = regex;
  }

  public static ObjectKeyPattern compile(String pattern) {
    Objects.requireNonNull(pattern, "pattern");
    var trimmed = pattern.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("Object pattern must not be empty");
    }
    return new ObjectKeyPattern(trimmed, literalPrefixOf(trimmed), Pattern.compile(toRegex(trimmed)));
  }

  public String literalPrefix() {
    return literalPrefix;
  }

  public boolean matches(String objectKey) {
    return objectKey != null && regex.matcher(objectKey).matches();
  }

  @Override
  public String toString() {
    return pattern;
  }

  private static String literalPrefixOf(String pattern) {
    var end = 0;
    while (end < pattern.length() && WILDCARDS.indexOf(pattern.charAt(end)) < 0) {
      end++;
    }
    return pattern.substring(0, end);
  }

  private static String toRegex(String pattern) {
    var out = new StringBuilder(pattern.length() * 2);
    var i = 0;
    while (i < pattern.length()) {
      var c = pattern.charAt(i);
      switch (c) {
        case '*' -> {
          if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
            out.append(".*");
            i++;
          } else {
            // A single star stays inside one key segment.
            out.append("[^/]*");
          }
        }
        case '?' -> out.append("[^/]");
        default -> out.append(Pattern.quote(String.valueOf(c)));
      }
      i++;
    }
    return out.toString();
  }
}
