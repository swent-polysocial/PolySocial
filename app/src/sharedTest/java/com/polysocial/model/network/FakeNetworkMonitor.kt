// Contributors: Claude (Opus 5.5, Anthropic) drafted the network fake for #225; Mohamed Khellaf
// (reviewed and revised).
package com.polysocial.model.network

/** [NetworkMonitor] for tests: set [online], and count how often the connection was checked. */
class FakeNetworkMonitor(var online: Boolean = true) : NetworkMonitor {
  var checks = 0
    private set

  override fun isOnline(): Boolean {
    checks++
    return online
  }
}
