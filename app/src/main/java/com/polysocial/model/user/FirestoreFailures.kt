// Contributors: Claude (the shared failure reporting, moved here from
// FirestoreUserProfileRepository
// to share it with FirestorePublicProfileRepository, #229).
package com.polysocial.model.user

import com.google.firebase.FirebaseException
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Runs [call] and turns its failures into results, so the caller never hangs on "saving": offline
 * becomes [offline]; any other Firebase failure, a Firebase task cancelled while the caller is
 * still running, or any other exception (for example a Firestore client that was shut down) becomes
 * [failed]. Cancelling the caller's coroutine still cancels it.
 */
internal suspend fun <T> reportFailures(offline: T, failed: T, call: suspend () -> T): T =
    try {
      call()
    } catch (e: FirebaseException) {
      if (e.isOffline()) offline else failed
    } catch (e: CancellationException) {
      currentCoroutineContext().ensureActive()
      failed
    } catch (e: Exception) {
      failed
    }

private fun FirebaseException.isOffline(): Boolean =
    this is FirebaseFirestoreException && code == FirebaseFirestoreException.Code.UNAVAILABLE
