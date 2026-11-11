// Contributors: Claude (Opus 5.5, Anthropic) drafted the network fake for #225; Mohamed Khellaf
// (reviewed and revised); OpenAI Codex (observable connectivity, #234).
package com.polysocial.model.network

import kotlinx.coroutines.flow.MutableStateFlow

/** [NetworkMonitor] for tests: set [online], and count how often the connection was checked. */
class FakeNetworkMonitor(online: Boolean = true) : NetworkMonitor {
  private val connection = MutableStateFlow(online)

  var online: Boolean
    get() = connection.value
    set(value) {
      connection.value = value
    }

  var checks = 0
    private set

  override fun isOnline(): Boolean {
    checks++
    return online
  }

  override fun observeOnline() = connection
}
