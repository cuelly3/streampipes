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

package org.apache.streampipes.extensions.connectors.s3.adapter;

import org.apache.streampipes.commons.exceptions.connect.AdapterException;
import org.apache.streampipes.extensions.api.connect.IAdapterConfiguration;
import org.apache.streampipes.extensions.api.connect.IEventCollector;
import org.apache.streampipes.extensions.api.connect.StreamPipesAdapter;
import org.apache.streampipes.extensions.api.connect.context.IAdapterGuessSchemaContext;
import org.apache.streampipes.extensions.api.connect.context.IAdapterRuntimeContext;
import org.apache.streampipes.extensions.api.extractor.IAdapterParameterExtractor;
import org.apache.streampipes.extensions.connectors.s3.client.S3ClientFactory;
import org.apache.streampipes.extensions.connectors.s3.client.S3ConnectionException;
import org.apache.streampipes.extensions.connectors.s3.client.S3ConnectionSettings;
import org.apache.streampipes.extensions.connectors.s3.client.S3ConnectionValidator;
import org.apache.streampipes.extensions.connectors.s3.route.AfterReadAction;
import org.apache.streampipes.extensions.connectors.s3.route.ObjectKeyPattern;
import org.apache.streampipes.extensions.connectors.s3.route.S3IngestRoute;
import org.apache.streampipes.extensions.connectors.s3.route.S3IngestSettings;
import org.apache.streampipes.extensions.connectors.s3.runtime.IdempotentRepositories;
import org.apache.streampipes.extensions.connectors.s3.runtime.S3ObjectProcessor;
import org.apache.streampipes.extensions.connectors.s3.runtime.ThrottledErrorReporter;
import org.apache.streampipes.extensions.management.connect.adapter.parser.CsvParser;
import org.apache.streampipes.extensions.management.connect.adapter.parser.JsonParsers;
import org.apache.streampipes.extensions.management.connect.adapter.parser.xml.XmlParser;
import org.apache.streampipes.model.connect.guess.SampleData;
import org.apache.streampipes.model.extensions.ExtensionAssetType;
import org.apache.streampipes.sdk.StaticProperties;
import org.apache.streampipes.sdk.builder.adapter.AdapterConfigurationBuilder;
import org.apache.streampipes.sdk.helpers.Alternatives;
import org.apache.streampipes.sdk.helpers.Labels;
import org.apache.streampipes.sdk.helpers.Locales;

import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.InputStream;
import java.util.Comparator;
import java.util.Optional;


public class S3Adapter implements StreamPipesAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(S3Adapter.class);

  public static final String ID = "org.apache.streampipes.connectors.s3.object";

  static final String ENDPOINT_URL = "endpoint-url";
  static final String REGION = "region";
  static final String ACCESS_KEY = "access-key";
  static final String SECRET_KEY = "secret-key";
  static final String BUCKET = "bucket";
  static final String OBJECT_PATTERN = "object-pattern";
  static final String AFTER_READ = "after-read";
  static final String ARCHIVE_GROUP = "archive-group";
  static final String ARCHIVE_BUCKET = "archive-bucket";
  static final String POLL_INTERVAL_MS = "poll-interval-ms";

  private static final long DEFAULT_POLL_INTERVAL_MS = 10_000L;
  private static final int MAX_MESSAGES_PER_POLL = 10;

  private CamelContext camelContext;
  private S3Client s3Client;

  @Override
  public IAdapterConfiguration declareConfig() {
    return AdapterConfigurationBuilder
        .create(ID, 0, S3Adapter::new)
        .withSupportedParsers(
            new JsonParsers(),
            new CsvParser(),
            new XmlParser()
        )
        .withAssets(ExtensionAssetType.DOCUMENTATION, ExtensionAssetType.ICON)
        .withLocales(Locales.EN)
        .requiredTextParameter(Labels.withId(ENDPOINT_URL))
        .requiredTextParameter(Labels.withId(REGION))
        .requiredTextParameter(Labels.withId(ACCESS_KEY))
        .requiredSecret(Labels.withId(SECRET_KEY))
        .requiredTextParameter(Labels.withId(BUCKET))
        .requiredTextParameter(Labels.withId(OBJECT_PATTERN))
        .requiredAlternatives(
            Labels.withId(AFTER_READ),
            Alternatives.from(Labels.withId(AfterReadAction.KEEP_PERSISTENT.internalId()), true),
            Alternatives.from(Labels.withId(AfterReadAction.KEEP_IN_MEMORY.internalId())),
            Alternatives.from(
                Labels.withId(AfterReadAction.MOVE.internalId()),
                StaticProperties.group(
                    Labels.withId(ARCHIVE_GROUP),
                    StaticProperties.stringFreeTextProperty(Labels.withId(ARCHIVE_BUCKET))
                )
            ),
            Alternatives.from(Labels.withId(AfterReadAction.DELETE.internalId()))
        )
        .requiredIntegerParameter(Labels.withId(POLL_INTERVAL_MS), (int) DEFAULT_POLL_INTERVAL_MS)
        .buildConfiguration();
  }

  @Override
  public void onAdapterStarted(
      IAdapterParameterExtractor extractor,
      IEventCollector collector,
      IAdapterRuntimeContext adapterRuntimeContext
  ) throws AdapterException {
    var connection = connectionSettings(extractor);
    var client = S3ClientFactory.create(connection);

    try {
      S3ConnectionValidator.validate(client, connection);
    } catch (S3ConnectionException e) {
      client.close();
      throw new AdapterException(e.getMessage(), e);
    }

    var ingestSettings = ingestSettings(extractor, connection);
    var processor = new S3ObjectProcessor(extractor.selectedParser(), collector);
    var errorReporter = new ThrottledErrorReporter(adapterRuntimeContext.getLogger());
    var repository = IdempotentRepositories.create(
        ingestSettings.afterRead(), extractor.getAdapterDescription().getElementId());

    try {
      var context = new DefaultCamelContext();
      context.setStreamCaching(false);
      context.getRegistry().bind("s3Client", client);
      context.addRoutes(new S3IngestRoute(ingestSettings, processor, repository, errorReporter));
      context.start();

      this.camelContext = context;
      this.s3Client = client;
      LOG.info("Started S3 adapter for s3://{}/{} ({})",
          connection.bucket(), ingestSettings.pattern(), ingestSettings.afterRead());
    } catch (Exception e) {
      client.close();
      throw new AdapterException("Could not start the S3 ingest route: " + e.getMessage(), e);
    }
  }

  @Override
  public void onAdapterStopped(
      IAdapterParameterExtractor extractor,
      IAdapterRuntimeContext adapterRuntimeContext
  ) throws AdapterException {
    try {
      if (camelContext != null) {
        camelContext.stop();
      }
    } catch (Exception e) {
      throw new AdapterException("Could not stop the S3 ingest route: " + e.getMessage(), e);
    } finally {
      camelContext = null;
      if (s3Client != null) {
        s3Client.close();
        s3Client = null;
      }
    }
  }

  @Override
  public SampleData onSampleDataRequested(
      IAdapterParameterExtractor extractor,
      IAdapterGuessSchemaContext adapterGuessSchemaContext
  ) throws AdapterException {
    var connection = connectionSettings(extractor);
    var pattern = ObjectKeyPattern.compile(
        extractor.getStaticPropertyExtractor().singleValueParameter(OBJECT_PATTERN, String.class));

    try (var client = S3ClientFactory.create(connection)) {
      S3ConnectionValidator.validate(client, connection);

      var firstMatch = firstMatchingObject(client, connection.bucket(), pattern);
      if (firstMatch.isEmpty()) {
        throw new AdapterException(
            ("No object in bucket '%s' matches the pattern '%s'. The schema is guessed from the "
                + "first matching object, so at least one must exist.")
                .formatted(connection.bucket(), pattern));
      }

      try (InputStream body = client.getObject(
          request -> request.bucket(connection.bucket()).key(firstMatch.get()))) {
        return extractor.selectedParser().getSampleData(body);
      }
    } catch (S3ConnectionException e) {
      throw new AdapterException(e.getMessage(), e);
    } catch (AdapterException e) {
      throw e;
    } catch (Exception e) {
      throw new AdapterException("Could not read a sample object: " + e.getMessage(), e);
    }
  }

  private Optional<String> firstMatchingObject(
      S3Client client, String bucket, ObjectKeyPattern pattern) {
    var request = ListObjectsV2Request.builder().bucket(bucket);
    if (!pattern.literalPrefix().isEmpty()) {
      request.prefix(pattern.literalPrefix());
    }
    return client.listObjectsV2Paginator(request.build())
        .contents().stream()
        .map(S3Object::key)
        .filter(pattern::matches)
        .min(Comparator.naturalOrder());
  }

  private S3ConnectionSettings connectionSettings(IAdapterParameterExtractor extractor) {
    var properties = extractor.getStaticPropertyExtractor();
    return new S3ConnectionSettings(
        properties.singleValueParameter(ENDPOINT_URL, String.class).strip(),
        properties.singleValueParameter(REGION, String.class).strip(),
        properties.singleValueParameter(ACCESS_KEY, String.class).strip(),
        properties.secretValue(SECRET_KEY),
        properties.singleValueParameter(BUCKET, String.class).strip()
    );
  }

  private S3IngestSettings ingestSettings(
      IAdapterParameterExtractor extractor, S3ConnectionSettings connection) {
    var properties = extractor.getStaticPropertyExtractor();
    var afterRead = AfterReadAction.fromInternalId(
        properties.selectedAlternativeInternalId(AFTER_READ));
    var archiveBucket = afterRead == AfterReadAction.MOVE
        ? properties.singleValueParameter(ARCHIVE_BUCKET, String.class)
        : null;
    var pollInterval = properties.singleValueParameter(POLL_INTERVAL_MS, Integer.class);

    return new S3IngestSettings(
        connection,
        ObjectKeyPattern.compile(properties.singleValueParameter(OBJECT_PATTERN, String.class)),
        afterRead,
        archiveBucket == null ? null : archiveBucket.strip(),
        pollInterval == null ? DEFAULT_POLL_INTERVAL_MS : pollInterval,
        MAX_MESSAGES_PER_POLL
    );
  }
}
