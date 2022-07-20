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

import app.cash.zipline.EventListener
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okio.ByteString

/**
 * Preferred construction is via [ZiplineManifest.create]
 * Constructor is still accessible from .copy() on data class, alternatively can implement without the data class but
 * with manual equals/hashCode to support value equality
 */
@Serializable
data class ZiplineManifest private constructor(
  /** This is an ordered map; its modules are always topologically sorted. */
  val modules: Map<String, ZiplineModule>,
  /**
   * JS module ID for the application (ie. "./alpha-app.js").
   * This will usually be the last module in the manifest once it is topologically sorted.
   */
  val mainModuleId: String,
  /** Fully qualified main function to start the application (ie. "zipline.main()"). */
  val mainFunction: String?,

  /**
   * A manifest may include many signatures, in order of preference. The keys of the map are the
   * signing key names. The values of the map are hex-encoded signatures.
   */
  val signatures: Map<String, String>,
) {
  init {
    require(modules.keys.toList().isTopologicallySorted { id -> modules[id]!!.dependsOnIds }) {
      "Modules are not topologically sorted and can not be loaded"
    }
  }

  companion object {
    fun create(
      modules: Map<String, ZiplineModule>,
      mainFunction: String? = null,
      mainModuleId: String? = null,
      signatures: Map<String, String> = mapOf(),
      ): ZiplineManifest {
      val sortedModuleIds = modules.keys
        .toList()
        .topologicalSort { id ->
          modules[id]?.dependsOnIds
            ?: throw IllegalArgumentException("Unexpected [id=$id] is not found in modules keys")
        }
      return ZiplineManifest(
        modules = sortedModuleIds.associateWith { id ->
          modules[id]
            ?: throw IllegalArgumentException("Unexpected [id=$id] is not found in modules keys")
        },
        mainModuleId = mainModuleId ?: sortedModuleIds.last(),
        mainFunction = mainFunction,
        signatures = signatures,
      )
    }

    fun ByteString.decodeToZiplineManifest(
      eventListener: EventListener,
      applicationName: String,
      url: String?,
    ) = try {
      Json.decodeFromString<ZiplineManifest>(this.utf8())
    } catch (e: Exception) {
      eventListener.manifestParseFailed(applicationName, url, e)
      throw e
    }
  }
}
