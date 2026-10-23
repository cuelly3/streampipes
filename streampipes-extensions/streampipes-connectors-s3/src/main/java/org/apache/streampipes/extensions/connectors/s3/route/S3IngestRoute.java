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

import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.spi.IdempotentRepository;
import software.amazon.awssdk.core.exception.SdkException;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.BiConsumer;


public class S3IngestRoute extends RouteBuilder {

  public static final String ROUTE_ID = "streampipes-s3-ingest";

  static final String KEY_HEADER = "CamelAwsS3Key";
  static final String ETAG_HEADER = "CamelAwsS3ETag";

  static final int MAX_REDELIVERIES = 3;
  static final long INITIAL_REDELIVERY_DELAY_MS = 1_000L;
  static final double REDELIVERY_BACKOFF_MULTIPLIER = 2.0;

  private final S3IngestSettings settings;
  private final S3ObjectHandler handler;
  private final IdempotentRepository idempotentRepository;
  private final BiConsumer<String, Exception> errorReporter;

  public S3IngestRoute(
      S3IngestSettings settings,
      S3ObjectHandler handler,
      IdempotentRepository idempotentRepository,
      BiConsumer<String, Exception> errorReporter
  ) {
    this.settings = settings;
    this.handler = handler;
    this.idempotentRepository = idempotentRepository;
    this.errorReporter = errorReporter;
  }

  @Override
  public void configure() {
    onException(PartialObjectException.class)
        .handled(true)
        .maximumRedeliveries(0)
        .process(this::report);

    onException(IOException.class, SdkException.class)
        .handled(true)
        .maximumRedeliveries(MAX_REDELIVERIES)
        .redeliveryDelay(INITIAL_REDELIVERY_DELAY_MS)
        .backOffMultiplier(REDELIVERY_BACKOFF_MULTIPLIER)
        .useExponentialBackOff()
        .retryAttemptedLogLevel(LoggingLevel.DEBUG)
        .process(this::report);

    onException(Exception.class)
        .handled(true)
        .maximumRedeliveries(0)
        .process(this::report);

    ProcessorDefinition<?> pipeline = from(endpointUri())
        .routeId(ROUTE_ID)
        .filter(exchange -> settings.pattern()
            .matches(exchange.getIn().getHeader(KEY_HEADER, String.class)));

    if (idempotentRepository != null) {
      pipeline = pipeline
          .idempotentConsumer(idempotencyExpression(), idempotentRepository)
          .eager(true)
          .removeOnFailure(false);
    }

    pipeline.process(exchange -> {
      var key = exchange.getIn().getHeader(KEY_HEADER, String.class);
      try (InputStream body = exchange.getIn().getBody(InputStream.class)) {
        handler.handle(key, body);
      }
    });
  }

  private void report(Exchange exchange) {
    var cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
    errorReporter.accept(exchange.getIn().getHeader(KEY_HEADER, String.class), cause);
  }

  private Expression idempotencyExpression() {
    return new Expression() {
      @Override
      public <T> T evaluate(Exchange exchange, Class<T> type) {
        var value = idempotencyKey(
            exchange.getIn().getHeader(KEY_HEADER, String.class),
            exchange.getIn().getHeader(ETAG_HEADER, String.class));
        return exchange.getContext().getTypeConverter().convertTo(type, exchange, value);
      }
    };
  }

  static String idempotencyKey(String objectKey, String etag) {
    var cleanedEtag = etag == null ? "" : etag.replace("\"", "");
    return objectKey + "|" + cleanedEtag;
  }

  private String endpointUri() {
    var uri = new StringBuilder("aws2-s3://").append(settings.connection().bucket())
        .append("?amazonS3Client=#s3Client")
        .append("&autoCreateBucket=false")
        .append("&includeBody=false")
        .append("&includeFolders=false")
        .append("&bridgeErrorHandler=true")
        .append("&delay=").append(settings.pollDelayMs())
        .append("&maxMessagesPerPoll=").append(settings.maxPerPoll());

    var prefix = settings.pattern().literalPrefix();
    if (!prefix.isEmpty()) {
      uri.append("&prefix=RAW(").append(prefix).append(")");
    }

    switch (settings.afterRead()) {
      case DELETE -> uri.append("&deleteAfterRead=true");
      case MOVE -> uri.append("&moveAfterRead=true")
          .append("&deleteAfterRead=true")
          .append("&destinationBucket=").append(settings.archiveBucket());
      case KEEP_IN_MEMORY, KEEP_PERSISTENT -> uri.append("&deleteAfterRead=false");
      default -> throw new IllegalStateException("Unhandled action: " + settings.afterRead());
    }
    return uri.toString();
  }
}
