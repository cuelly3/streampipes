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

import org.apache.streampipes.extensions.api.monitoring.IExtensionsLogger;
import org.apache.streampipes.extensions.connectors.s3.route.PartialObjectException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

public class ThrottledErrorReporter implements BiConsumer<String, Exception> {

  static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(1);

  private final IExtensionsLogger logger;
  private final long intervalMillis;
  private final LongSupplier clock;

  private final AtomicLong suppressedSinceLastReport = new AtomicLong();
  private final AtomicLong lastReportedAt = new AtomicLong(Long.MIN_VALUE);
  private volatile String lastSignature;

  public ThrottledErrorReporter(IExtensionsLogger logger) {
    this(logger, DEFAULT_INTERVAL, System::currentTimeMillis);
  }

  ThrottledErrorReporter(IExtensionsLogger logger, Duration interval, LongSupplier clock) {
    this.logger = logger;
    this.intervalMillis = interval.toMillis();
    this.clock = clock;
  }

  @Override
  public void accept(String objectKey, Exception error) {
    var signature = signatureOf(objectKey, error);
    var now = clock.getAsLong();

    synchronized (this) {
      var isNewFault = !signature.equals(lastSignature);
      var intervalElapsed = now - lastReportedAt.get() >= intervalMillis;

      if (isNewFault) {
        lastSignature = signature;
        suppressedSinceLastReport.set(0);
        lastReportedAt.set(now);
        report(objectKey, error, 0);
        return;
      }

      if (intervalElapsed) {
        var suppressed = suppressedSinceLastReport.getAndSet(0);
        lastReportedAt.set(now);
        report(objectKey, error, suppressed);
        return;
      }

      suppressedSinceLastReport.incrementAndGet();
    }
  }

  private void report(String objectKey, Exception error, long suppressed) {
    var where = objectKey == null ? "while polling" : "for object '%s'".formatted(objectKey);
    var repeats = suppressed == 0
        ? ""
        : " (%d further identical failure(s) suppressed)".formatted(suppressed);

    if (error instanceof PartialObjectException partial) {
      logger.warn(
          "S3 object partially ingested",
          ("Parsing '%s' failed after %d event(s) had already been emitted. The object was not "
              + "retried, because a retry would duplicate those events.%s")
              .formatted(partial.objectKey(), partial.eventsEmitted(), repeats));
      return;
    }
    logger.error("S3 adapter error %s: %s%s".formatted(where, error.getMessage(), repeats), error);
  }

  private static String signatureOf(String objectKey, Exception error) {
    return error.getClass().getName() + "|" + String.valueOf(error.getMessage()) + "|" + objectKey;
  }
}
