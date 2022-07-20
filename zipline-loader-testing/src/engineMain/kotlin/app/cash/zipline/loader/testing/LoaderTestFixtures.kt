/*
 * Copyright (C) 2022 Square, Inc.
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

package app.cash.zipline.loader.testing

import app.cash.zipline.QuickJs
import app.cash.zipline.loader.CURRENT_ZIPLINE_VERSION
import app.cash.zipline.loader.ZiplineFile
import app.cash.zipline.loader.ZiplineManifest
import app.cash.zipline.loader.ZiplineModule
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

class LoaderTestFixtures {
  val alphaJs = createJs("alpha")
  val alphaByteString = createZiplineFile(alphaJs, "alpha.js")
  val alphaSha256 = alphaByteString.sha256()
  val alphaSha256Hex = alphaSha256.hex()

  val bravoJs = createJs("bravo")
  val bravoByteString = createZiplineFile(bravoJs, "bravo.js")
  val bravoSha256 = bravoByteString.sha256()
  val bravoSha256Hex = bravoSha256.hex()

  val manifestWithRelativeUrls = ZiplineManifest.create(
    modules = mapOf(
      "bravo" to ZiplineModule(
        url = bravoRelativeUrl,
        sha256 = bravoByteString.sha256(),
        dependsOnIds = listOf("alpha"),
      ),
      "alpha" to ZiplineModule(
        url = alphaRelativeUrl,
        sha256 = alphaByteString.sha256(),
        dependsOnIds = listOf(),
      ),
    ),
    mainFunction = "zipline.ziplineMain()"
  )

  val manifestWithRelativeUrlsJsonString = Json.encodeToString(manifestWithRelativeUrls)
  val manifestWithRelativeUrlsByteString = manifestWithRelativeUrlsJsonString.encodeUtf8()

  val manifest = manifestWithRelativeUrls.copy(
    modules = manifestWithRelativeUrls.modules.mapValues { (_, module) ->
      module.copy(
        url = when (module.url) {
          bravoRelativeUrl -> bravoUrl
          alphaRelativeUrl -> alphaUrl
          else -> error("unexpected URL: ${module.url}")
        }
      )
    }
  )

  val manifestJsonString = Json.encodeToString(manifest)
  val manifestByteString = manifestJsonString.encodeUtf8()

  fun createZiplineFile(javaScript: String, fileName: String): ByteString {
    val quickJs = QuickJs.create()
    val compiledJavaScript = try {
      quickJs.compile(javaScript, fileName)
    } finally {
      quickJs.close()
    }
    val ziplineFile = ZiplineFile(
      CURRENT_ZIPLINE_VERSION,
      compiledJavaScript.toByteString()
    )
    val buffer = Buffer()
    ziplineFile.writeTo(buffer)
    return buffer.readByteString()
  }

  companion object {
    const val alphaRelativeUrl = "alpha.zipline"
    const val bravoRelativeUrl = "bravo.zipline"
    const val alphaUrl = "https://example.com/files/alpha.zipline"
    const val bravoUrl = "https://example.com/files/bravo.zipline"
    const val manifestUrl = "https://example.com/files/default.manifest.zipline.json"

    fun createRelativeManifest(
      seed: String,
      seedFileSha256: ByteString
    ) = ZiplineManifest.create(
      modules = mapOf(
        seed to ZiplineModule(
          url = "$seed.zipline",
          sha256 = seedFileSha256,
        )
      ),
      mainFunction = "zipline.ziplineMain()"
    )

    // TODO make these real like the examples in LoadJsModuleTest so they load
    fun createJs(seed: String) = jsBoilerplate(
      seed = seed,
      loadBody = """
              globalThis.log = globalThis.log || "";
              globalThis.log += "$seed loaded\n";
            """.trimIndent(),
      mainBody = """
              globalThis.mainLog = globalThis.mainLog || "";
              globalThis.mainLog += "$seed loaded\n";
            """.trimIndent()
    )

    fun createFailureJs(seed: String) = jsBoilerplate(
      seed = seed,
      loadBody = "throw Error('$seed');",
      mainBody = "throw Error('$seed');"
    )

    private fun jsBoilerplate(seed: String, loadBody: String, mainBody: String) = """
      (function (root, factory) {
        if (typeof define === 'function' && define.amd)
          define(['exports'], factory);
        else if (typeof exports === 'object')
          factory(module.exports);
        else
          root.zipline_main = factory(typeof zipline_main === 'undefined' ? {} : zipline_main);
      }(this, function (_) {
         function ziplineMain() {
           $mainBody
         }
         //region block: exports
         function ${'$'}jsExportAll${'$'}(_) {
           // export global value for module name for easier test manipulation
           globalThis.seed = '$seed';

           // run test body code
           $loadBody

           // export scoped main function
           var ${'$'}zipline = _.zipline || (_.zipline = {});
           ${'$'}zipline.ziplineMain = ziplineMain;
         }
         ${'$'}jsExportAll${'$'}(_);
         //endregion
         return _;
      }));
      """.trimIndent()
  }
}
