// Contributors: Claude (Fable 5.1, Anthropic) drafted the account deletion fake for #206; Mohamed Khellaf (reviewed and revised).
package com.polysocial.model.account

import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory [AccountDeletionRepository] for tests: set the next [result], count the calls, and
 * optionally hold a call on [gate] to observe the deleting state or cancel it.
 */
class FakeAccountDeletionRepository(
    var result: AccountDeletionResult = AccountDeletionResult.Deleted
) : AccountDeletionRepository {
  /** When set, [deleteAccount] stays suspended until the test completes it. */
  var gate: CompletableDeferred<Unit>? = null

  var calls = 0
    private set

  override suspend fun deleteAccount(): AccountDeletionResult {
    calls++
    gate?.await()
    return result
  }
}
