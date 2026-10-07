// Contributors: Claude (Firestore profile repository, #34).
package com.polysocial.model.user

import com.google.firebase.FirebaseException
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.tasks.await

/** Firestore collection holding the private profiles, one document per Firebase Auth UID. */
const val USERS_COLLECTION = "users"

/**
 * [UserProfileRepository] backed by Firestore. Documents are converted to and from maps by hand, so
 * the stored field names are exactly the ones the Security Rules check (see CONTEXT.md).
 *
 * Writes run in a transaction: it reads the document before writing, which is how "create once" and
 * "update only if it exists" are checked, and it needs the server, so offline it fails instead of
 * being queued like a plain write.
 */
class FirestoreUserProfileRepository(private val db: FirebaseFirestore) : UserProfileRepository {

  private fun document(uid: String): DocumentReference =
      db.collection(USERS_COLLECTION).document(uid)

  override suspend fun getProfile(uid: String): ProfileResult =
      try {
        val snapshot = document(uid).get().await()
        if (!snapshot.exists()) ProfileResult.NotFound
        else
            snapshot.toUserProfile()?.let { ProfileResult.Found(it) }
                ?: ProfileResult.UnexpectedError
      } catch (e: FirebaseException) {
        if (e.isOffline()) ProfileResult.NetworkError else ProfileResult.UnexpectedError
      }

  override suspend fun createProfile(profile: UserProfile): CreateProfileResult =
      try {
        val ref = document(profile.uid)
        val created =
            db.runTransaction { transaction ->
                  if (transaction.get(ref).exists()) false
                  else {
                    transaction.set(ref, profile.toNewDocument())
                    true
                  }
                }
                .await()
        if (created) CreateProfileResult.Created else CreateProfileResult.AlreadyExists
      } catch (e: FirebaseException) {
        if (e.isOffline()) CreateProfileResult.NetworkError else CreateProfileResult.UnexpectedError
      }

  override suspend fun updateProfile(profile: UserProfile): UpdateProfileResult =
      try {
        val ref = document(profile.uid)
        val updated =
            db.runTransaction { transaction ->
                  if (!transaction.get(ref).exists()) false
                  else {
                    transaction.update(ref, profile.toEditableFields())
                    true
                  }
                }
                .await()
        if (updated) UpdateProfileResult.Updated else UpdateProfileResult.NotFound
      } catch (e: FirebaseException) {
        if (e.isOffline()) UpdateProfileResult.NetworkError else UpdateProfileResult.UnexpectedError
      }
}

private fun FirebaseException.isOffline(): Boolean =
    this is FirebaseFirestoreException && code == FirebaseFirestoreException.Code.UNAVAILABLE

/** The fields of a new `users/{uid}` document. `isAssociationVerified` is never written here. */
private fun UserProfile.toNewDocument(): Map<String, Any> =
    toEditableFields() +
        mapOf(
            "uid" to uid,
            "email" to email,
            "accountType" to accountType.value,
            "createdAt" to FieldValue.serverTimestamp(),
        )

private fun UserProfile.toEditableFields(): Map<String, Any> =
    mapOf("displayName" to displayName, "section" to section, "year" to year)

/** The profile in this document, or `null` if a required field is missing or unknown. */
private fun DocumentSnapshot.toUserProfile(): UserProfile? {
  val accountType =
      AccountType.entries.firstOrNull { it.value == getString("accountType") } ?: return null
  return UserProfile(
      uid = getString("uid") ?: return null,
      email = getString("email") ?: return null,
      displayName = getString("displayName") ?: return null,
      section = getString("section") ?: return null,
      year = getString("year") ?: return null,
      accountType = accountType,
      createdAt = getTimestamp("createdAt")?.toInstant(),
  )
}
