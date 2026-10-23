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

import org.apache.streampipes.commons.environment.Environments;
import org.apache.streampipes.extensions.connectors.s3.route.AfterReadAction;

import org.apache.camel.spi.IdempotentRepository;
import org.apache.camel.support.processor.idempotent.FileIdempotentRepository;
import org.apache.camel.support.processor.idempotent.MemoryIdempotentRepository;

import java.nio.file.Path;


public final class IdempotentRepositories {


  static final int CACHE_SIZE = 10_000;

  static final long MAX_FILE_STORE_BYTES = 8L * 1024 * 1024;
  static final long DROP_OLDEST_BYTES = 2L * 1024 * 1024;

  private IdempotentRepositories() {
  }

 
  public static IdempotentRepository create(AfterReadAction action, String adapterElementId) {
    return switch (action) {
      case KEEP_IN_MEMORY -> MemoryIdempotentRepository.memoryIdempotentRepository(CACHE_SIZE);
      case KEEP_PERSISTENT -> fileRepository(adapterElementId);
      case MOVE, DELETE -> null;
    };
  }

  private static IdempotentRepository fileRepository(String adapterElementId) {
    var repository = FileIdempotentRepository.fileIdempotentRepository(
        storeFile(adapterElementId).toFile(), CACHE_SIZE, MAX_FILE_STORE_BYTES);
    if (repository instanceof FileIdempotentRepository fileRepository) {
      fileRepository.setDropOldestFileStore(DROP_OLDEST_BYTES);
    }
    return repository;
  }

  static Path storeFile(String adapterElementId) {
    var baseDir = Environments
        .getEnvironment()
        .getExtAssetBaseDir()
        .getValueOrReturn(System.getProperty("user.home"));

    return Path.of(baseDir, ".streampipes", "service", "s3-idempotent",
        sanitize(adapterElementId) + ".txt");
  }

  private static String sanitize(String adapterElementId) {
    return adapterElementId.replaceAll("[^A-Za-z0-9._-]", "_");
  }
}
