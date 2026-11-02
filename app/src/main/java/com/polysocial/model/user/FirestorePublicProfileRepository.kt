// Contributors: Claude (Firestore public profile repository and its stored fields, #229; several
// profiles read one document each, in parallel, after review, #229).
package com.polysocial.model.user

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

/** Firestore collection holding the public profiles, one document per student's Auth UID. */
const val PUBLIC_PROFILES_COLLECTION = "publicProfiles"

/**
 * [PublicProfileRepository] backed by Firestore. Documents are read by hand, so the field names are
 * exactly the ones the Security Rules check (see CONTEXT.md). Several profiles are read as one
 * document read per UID, in parallel, never as a query: the rules allow reads by UID only, so no
 * student can list the whole collection. Firestore bills each document a query returns as one read,
 * so this costs the same as a query; the caller bounds how many UIDs it asks for.
 */
class FirestorePublicProfileRepository(private val database: () -> FirebaseFirestore) :
    PublicProfileRepository {
  constructor(db: FirebaseFirestore) : this({ db })

  private val collection
    get() = database().collection(PUBLIC_PROFILES_COLLECTION)

  override suspend fun getPublicProfile(uid: String): PublicProfileResult =
      reportFailures(PublicProfileResult.NetworkError, PublicProfileResult.UnexpectedError) {
        val snapshot = collection.document(uid).get().await()
        if (!snapshot.exists()) PublicProfileResult.NotFound
        else
            snapshot.toPublicProfile()?.let { PublicProfileResult.Found(it) }
                ?: PublicProfileResult.UnexpectedError
      }

  override suspend fun getPublicProfiles(uids: Collection<String>): PublicProfilesResult {
    val wanted = uids.distinct()
    if (wanted.isEmpty()) return PublicProfilesResult.Found(emptyMap())
    return reportFailures(PublicProfilesResult.NetworkError, PublicProfilesResult.UnexpectedError) {
      // One failed read fails them all and cancels the others: offline, a profile that isn't
      // cached may still exist on the server, so a partial answer could be wrong.
      val snapshots = coroutineScope {
        wanted.map { uid -> async { collection.document(uid).get().await() } }.awaitAll()
      }
      PublicProfilesResult.Found(
          snapshots
              .filter { it.exists() }
              .mapNotNull { it.toPublicProfile() }
              .associateBy { it.uid }
      )
    }
  }
}

/**
 * The `publicProfiles/{uid}` document of this private profile: exactly the fields the Security
 * Rules require, copied from it, and no interests until #230. It never holds the email.
 */
internal fun UserProfile.toPublicProfileDocument(): Map<String, Any> =
    mapOf(
        "displayName" to displayName,
        "section" to section,
        "year" to year,
        "interests" to emptyList<String>(),
    )

/**
 * The public profile in this document, or `null` if a field is missing or stored with the wrong
 * type (Firestore throws for a field of another type).
 */
private fun DocumentSnapshot.toPublicProfile(): PublicProfile? =
    try {
      readPublicProfile()
    } catch (e: RuntimeException) {
      null
    }

private fun DocumentSnapshot.readPublicProfile(): PublicProfile? {
  val interests = get("interests") as? List<*> ?: return null
  return PublicProfile(
      uid = id,
      displayName = getString("displayName") ?: return null,
      section = getString("section") ?: return null,
      year = getString("year") ?: return null,
      interests = interests.map { it as? String ?: return null }.toSet(),
  )
}
