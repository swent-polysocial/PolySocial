// Contributors: Claude (Opus 5.5, Anthropic) (drafted the Firestore username repository, #448).
package com.polysocial.model.user

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.network.NetworkMonitor
import kotlinx.coroutines.tasks.await

/** Firestore collection holding the usernames, one document per name, holding its `uid`. */
const val USERNAMES_COLLECTION = "usernames"

/**
 * [UsernameRepository] backed by Firestore: `usernames/{name}` holds `{uid}`, and the student's
 * `users/{uid}` and `publicProfiles/{uid}` name it in [USERNAME_FIELD].
 *
 * Like the other repositories, it checks [NetworkMonitor] first, and [setUsername] runs in a
 * transaction, which Firestore only runs against the server, so nothing is queued offline. The
 * transaction keeps Firestore's retries: when two students claim the same free name at once, the
 * second commit fails on the first one's document, and the retry reads it and returns
 * [SetUsernameResult.Taken]. [lookUp] reads from the server only, never the cache.
 */
class FirestoreUsernameRepository(
    private val database: () -> FirebaseFirestore,
    private val auth: AuthRepository,
    private val network: NetworkMonitor,
) : UsernameRepository {
  constructor(
      db: FirebaseFirestore,
      auth: AuthRepository,
      network: NetworkMonitor,
  ) : this({ db }, auth, network)

  private val db: FirebaseFirestore
    get() = database()

  private fun name(name: String): DocumentReference =
      db.collection(USERNAMES_COLLECTION).document(name)

  override suspend fun lookUp(name: String): UsernameLookupResult {
    if (usernameError(name) != null) return UsernameLookupResult.NotFound
    if (!network.isOnline()) return UsernameLookupResult.NetworkError
    return reportFailures(UsernameLookupResult.NetworkError, UsernameLookupResult.UnexpectedError) {
      val snapshot = name(name).get(Source.SERVER).await()
      when {
        !snapshot.exists() -> UsernameLookupResult.NotFound
        else ->
            snapshot.getString("uid")?.let { UsernameLookupResult.Found(it) }
                ?: UsernameLookupResult.UnexpectedError
      }
    }
  }

  override suspend fun setUsername(name: String): SetUsernameResult {
    usernameError(name)?.let {
      return SetUsernameResult.Invalid(it)
    }
    if (!network.isOnline()) return SetUsernameResult.NetworkError
    val uid = auth.currentUser()?.uid ?: return SetUsernameResult.UnexpectedError
    return reportFailures(SetUsernameResult.NetworkError, SetUsernameResult.UnexpectedError) {
      db.runTransaction { transaction -> claim(transaction, uid, name) }.await()
    }
  }

  /**
   * Gives [uid] the name [name] in [transaction]: reads their profile, then (for a student) the
   * name and their public profile, then writes. The Security Rules accept the writes only together:
   * the new name document, both profiles naming it (the public one only if it exists, as for a
   * profile saved before #229), and the previous name deleted. A name document already [uid]'s,
   * which their profile doesn't name (only an admin's edit could leave one), is kept and named.
   */
  private fun claim(transaction: Transaction, uid: String, name: String): SetUsernameResult {
    val profile = db.collection(USERS_COLLECTION).document(uid)
    val stored = transaction.get(profile)
    // Before reading usernames, which the rules deny an association.
    if (!stored.exists() || stored.getString("accountType") == AccountType.ASSOCIATION.value) {
      return SetUsernameResult.UnexpectedError
    }
    val previous = stored.get(USERNAME_FIELD) as? String
    if (previous == name) return SetUsernameResult.Saved
    val holder = transaction.get(name(name))
    val heldByMe = holder.exists() && holder.getString("uid") == uid
    if (holder.exists() && !heldByMe) return SetUsernameResult.Taken
    val publicProfile = db.collection(PUBLIC_PROFILES_COLLECTION).document(uid)
    val hasPublicProfile = transaction.get(publicProfile).exists()

    if (!heldByMe) transaction.set(name(name), mapOf("uid" to uid))
    transaction.update(profile, USERNAME_FIELD, name)
    if (hasPublicProfile) transaction.update(publicProfile, USERNAME_FIELD, name)
    previous?.takeIf { usernameError(it) == null }?.let { transaction.delete(name(it)) }
    return SetUsernameResult.Saved
  }
}
