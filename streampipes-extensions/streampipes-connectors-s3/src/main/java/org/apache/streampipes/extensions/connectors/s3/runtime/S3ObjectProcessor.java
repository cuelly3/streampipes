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

package org.apache.streampipes.extensions.connectors.s3.runtime;

import org.apache.streampipes.extensions.api.connect.IEventCollector;
import org.apache.streampipes.extensions.api.connect.IParser;
import org.apache.streampipes.extensions.connectors.s3.route.PartialObjectException;
import org.apache.streampipes.extensions.connectors.s3.route.S3ObjectHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The single point where the Camel side meets StreamPipes: takes an object's
 * stream, runs the user-selected parser over it, and forwards each event to the
 * collector.
 *
 * <p>{@link IParser#parse} already accepts an {@code InputStream}, so nothing is
 * buffered here.
 */
public class S3ObjectProcessor implements S3ObjectHandler {

  private static final Logger LOG = LoggerFactory.getLogger(S3ObjectProcessor.class);

  private final IParser parser;
  private final IEventCollector collector;
  private final AtomicLong objectsProcessed = new AtomicLong();
  private final AtomicLong eventsEmitted = new AtomicLong();

  public S3ObjectProcessor(IParser parser, IEventCollector collector) {
    this.parser = parser;
    this.collector = collector;
  }

  @Override
  public void handle(String objectKey, InputStream body) throws Exception {
    var emittedForObject = new AtomicLong();
    try {
      parser.parse(body, event -> {
        collector.collect(event);
        emittedForObject.incrementAndGet();
        eventsEmitted.incrementAndGet();
      });
      objectsProcessed.incrementAndGet();
      LOG.debug("Parsed {} into {} event(s)", objectKey, emittedForObject.get());
    } catch (Exception e) {
      // A mid-parse failure leaves events already emitted. There is no
      // transaction spanning the broker and the idempotent repository, so a
      // retry would duplicate them: report how far it got and move on.
      throw new PartialObjectException(objectKey, emittedForObject.get(), e);
    }
  }

  public long objectsProcessed() {
    return objectsProcessed.get();
  }

  public long eventsEmitted() {
    return eventsEmitted.get();
  }
}
