/*
 * Copyright (C) 2021 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.zipline.loader

import app.cash.zipline.Zipline
import com.squareup.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * Gets code from an HTTP server or a local cache,
 * and loads it into a zipline instance. This attempts
 * to load code as quickly as possible, and will
 * concurrently download and load code.
 */
class ZiplineLoader(
  private val dispatcher: CoroutineDispatcher,
  private val httpClient: ZiplineHttpClient,
  private val embeddedDirectory: Path,
  private val embeddedFileSystem: FileSystem,
  cacheDbDriver: SqlDriver, // SqlDriver is already initialized to the platform and SQLite DB on disk
  cacheDirectory: Path,
  cacheFileSystem: FileSystem,
  cacheMaxSizeInBytes: Int = 100 * 1024 * 1024,
  nowMs: () -> Long, // 100 MiB
) {
  private var concurrentDownloadsSemaphore = Semaphore(3)
  var concurrentDownloads = 3
    set(value) {
      require(value > 0)
      field = value
      concurrentDownloadsSemaphore = Semaphore(value)
    }

  // TODO add schema version checker and automigration
  private val cache = createZiplineCache(
    driver = cacheDbDriver,
    fileSystem = cacheFileSystem,
    directory = cacheDirectory,
    maxSizeInBytes = cacheMaxSizeInBytes.toLong(),
    nowMs = nowMs
  )

  suspend fun load(zipline: Zipline, url: String) {
    // TODO fallback to manifest shipped in resources for offline support
    val manifestByteString = try {
      concurrentDownloadsSemaphore.withPermit {
        httpClient.download(url)
      }
    } catch (e: IOException) {
      // If manifest fails to load over network, fallback to prebuilt in resources
      val prebuiltManifestPath = embeddedDirectory / PREBUILT_MANIFEST_FILE_NAME
      embeddedFileSystem.read(prebuiltManifestPath) {
        readByteString()
      }
    }

    val manifest = Json.decodeFromString<ZiplineManifest>(manifestByteString.utf8())

    load(zipline, manifest)
  }

  suspend fun load(zipline: Zipline, manifest: ZiplineManifest) {
    coroutineScope {
      val loads = manifest.modules.map {
        ModuleLoad(zipline, it.key, it.value)
      }
      for (load in loads) {
        val loadJob = launch { load.load() }

        val downstreams = loads.filter { load.id in it.module.dependsOnIds }
        for (downstream in downstreams) {
          downstream.upstreams += loadJob
        }
      }
    }
  }

  private inner class ModuleLoad(
    val zipline: Zipline,
    val id: String,
    val module: ZiplineModule,
  ) {
    val upstreams = mutableListOf<Job>()

    /** Attempt to load from, in prioritized order: embedded, cache, network */
    suspend fun load() {
      val embeddedPath = embeddedDirectory / module.sha256
      val ziplineFileBytes = if (embeddedFileSystem.exists(embeddedPath)) {
        embeddedFileSystem.read(embeddedPath, BufferedSource::readByteString)
      } else {
        cache.getOrPut(module.sha256) {
          concurrentDownloadsSemaphore.withPermit {
            httpClient.download(module.url)
          }
        }
      }

      val ziplineFile = ZiplineFile.read(Buffer().write(ziplineFileBytes))
      upstreams.joinAll()
      withContext(dispatcher) {
        zipline.multiplatformLoadJsModule(ziplineFile.quickjsBytecode.toByteArray(), id)
      }
    }
  }

  /** For downloading patches instead of full-sized files. */
  suspend fun localFileHashes(): List<ByteString> {
    TODO()
  }

  companion object {
    internal const val PREBUILT_MANIFEST_FILE_NAME = "manifest.zipline.json"
  }
}
