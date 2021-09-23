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
package app.cash.zipline.internal

import app.cash.zipline.Zipline
import java.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal actual fun createHostPlatform(
  scope: CoroutineScope,
  jsPlatform: JsPlatform,
): HostPlatform {
  return RealHostPlatform(scope, jsPlatform)
}

private class RealHostPlatform(
  val scope: CoroutineScope,
  val jsPlatform: JsPlatform,
) : HostPlatform {
  private val logger = Logger.getLogger(Zipline::class.qualifiedName)

  override fun setTimeout(timeoutId: Int, delayMillis: Int) {
    scope.launch(start = UNDISPATCHED) {
      delay(delayMillis.toLong())
      jsPlatform.runJob(timeoutId)
    }
  }

  override fun consoleMessage(level: String, message: String) {
    when (level) {
      "warn" -> logger.warning(message)
      "error" -> logger.severe(message)
      else -> logger.info(message)
    }
  }
}