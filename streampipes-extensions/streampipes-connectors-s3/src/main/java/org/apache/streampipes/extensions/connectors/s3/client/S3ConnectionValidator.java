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

package org.apache.streampipes.extensions.connectors.s3.client;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;


public final class S3ConnectionValidator {

  private S3ConnectionValidator() {
  }

  public static void validate(S3Client client, S3ConnectionSettings settings)
      throws S3ConnectionException {
    try {
      client.listObjectsV2(request -> request.bucket(settings.bucket()).maxKeys(1));
    } catch (NoSuchBucketException e) {
      throw new S3ConnectionException(
          "Bucket '%s' does not exist at %s.".formatted(settings.bucket(), settings.endpointUrl()), e);
    } catch (S3Exception e) {
      var status = e.statusCode();
      if (status == 401 || status == 403) {
        throw new S3ConnectionException(
            ("Access to bucket '%s' at %s was denied (HTTP %d). Check the access key, the secret "
                + "key and the permissions of that key.")
                .formatted(settings.bucket(), settings.endpointUrl(), status), e);
      }
      if (status == 404) {
        throw new S3ConnectionException(
            "Bucket '%s' does not exist at %s.".formatted(settings.bucket(), settings.endpointUrl()), e);
      }
      throw new S3ConnectionException(
          "The endpoint %s rejected the request with HTTP %d: %s"
              .formatted(settings.endpointUrl(), status, firstLine(e.getMessage())), e);
    } catch (SdkClientException e) {
      // Never got an answer at all.
      throw new S3ConnectionException(
          ("Could not reach the S3 endpoint at %s. Check the endpoint URL, and remember that inside "
              + "a container 'localhost' is that container itself.")
              .formatted(settings.endpointUrl()), e);
    } catch (RuntimeException e) {
      throw new S3ConnectionException(
          "Could not connect to %s: %s".formatted(settings.endpointUrl(), firstLine(e.getMessage())), e);
    }
  }

  private static String firstLine(String message) {
    if (message == null) {
      return "(no message)";
    }
    var newline = message.indexOf('\n');
    return newline < 0 ? message : message.substring(0, newline);
  }
}
