// Contributors: OpenAI Codex (GPT-6.2 Sol, high; #450 under Lucas Pinto Ricardo's supervision and
// approval).
package com.polysocial.model.user

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.firebase.ProfileFriendsCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val LIST_READ_TIMEOUT_MILLIS = 5_000L

/** Uses Firestore for the rows and checks for a complete prior server result on cache fallback. */
class FirestoreProfileFriendsRepository(
    private val database: () -> FirebaseFirestore,
    private val auth: AuthRepository,
    private val cache: ProfileFriendsCache,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ProfileFriendsRepository {
  override suspend fun read(profile: PublicProfile, online: Boolean): ProfileFriendsResult =
      withContext(io) {
        reportFailures(ProfileFriendsResult.Offline, ProfileFriendsResult.Error) {
          val viewer = auth.currentUser()?.uid ?: return@reportFailures ProfileFriendsResult.Error
          val owner = profile.uid
          val query =
              database()
                  .collection(PUBLIC_PROFILES_COLLECTION)
                  .document(owner)
                  .collection("friends")
          val confirmed =
              if (online) {
                try {
                  withTimeoutOrNull(LIST_READ_TIMEOUT_MILLIS) {
                    query.get(Source.SERVER).await().documents.map { it.id }
                  }
                } catch (failure: FirebaseFirestoreException) {
                  when (failure.code) {
                    FirebaseFirestoreException.Code.PERMISSION_DENIED -> {
                      cache.forget(viewer, owner)
                      return@reportFailures ProfileFriendsResult.Denied
                    }
                    FirebaseFirestoreException.Code.UNAVAILABLE -> null
                    else -> throw failure
                  }
                }
              } else null

          if (confirmed != null) {
            cache.remember(viewer, profile, confirmed)
            ProfileFriendsResult.Found(confirmed)
          } else {
            val cached = query.get(Source.CACHE).await().documents.map { it.id }
            if (cache.matches(viewer, profile, cached)) {
              ProfileFriendsResult.Found(cached, fromCache = true)
            } else ProfileFriendsResult.Offline
          }
        }
      }
}
