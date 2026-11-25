// Contributors: OpenAI Codex (GPT-6.2 Sol, high; profile visibility and bio for #446 under
// Lucas Pinto Ricardo’s supervision and approval).
// Contributors: OpenAI Codex (refresh Firestore and handle blocked cache access, #156).
// Contributors: Claude (Firestore profile repository, #34; cancelled tasks and wrong-typed fields
// reported as errors after review; reading isAssociationVerified, #45; other unexpected failures
// reported as errors, #182; the student's public profile written in the same transaction, #229;
// reportFailures moved to FirestoreFailures.kt to share it, after review, #229; interests, #230;
// the missing public profile repaired, #291).
// Contributors: Claude (Opus 5.5, Anthropic) (the username read and kept in the public copy, #448).
package com.polysocial.model.user

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/** Firestore collection holding the private profiles, one document per Firebase Auth UID. */
const val USERS_COLLECTION = "users"

/** The field of `users/{uid}` and `publicProfiles/{uid}` naming the student's username (#448). */
internal const val USERNAME_FIELD = "username"

/**
 * [UserProfileRepository] backed by Firestore. Documents are converted to and from maps by hand, so
 * the stored field names are exactly the ones the Security Rules check (see CONTEXT.md).
 *
 * Writes run in a transaction: it reads the document before writing, which is how "create once" and
 * "update only if it exists" are checked, and it needs the server, so offline it fails instead of
 * being queued like a plain write. A student's public profile, `publicProfiles/{uid}` (#229), is
 * written in the same transaction, so the app always saves the two together, and the Security Rules
 * accept the public one only as a copy of `users/{uid}` as it is after that write, so it never
 * holds anything but its owner's own values. An association gets none; the stored account type
 * decides on an update.
 */
class FirestoreUserProfileRepository(private val database: () -> FirebaseFirestore) :
    UserProfileRepository {
  constructor(db: FirebaseFirestore) : this({ db })

  private val db: FirebaseFirestore
    get() = database()

  private fun document(uid: String): DocumentReference =
      db.collection(USERS_COLLECTION).document(uid)

  private fun publicDocument(uid: String): DocumentReference =
      db.collection(PUBLIC_PROFILES_COLLECTION).document(uid)

  override suspend fun getProfile(uid: String): ProfileResult =
      reportFailures(ProfileResult.NetworkError, ProfileResult.UnexpectedError) {
        val snapshot = document(uid).get().await()
        if (!snapshot.exists()) ProfileResult.NotFound
        else
            snapshot.toUserProfile()?.let { ProfileResult.Found(it) }
                ?: ProfileResult.UnexpectedError
      }

  override suspend fun createProfile(profile: UserProfile): CreateProfileResult =
      reportFailures(CreateProfileResult.NetworkError, CreateProfileResult.UnexpectedError) {
        profile.requireValidBio()
        val ref = document(profile.uid)
        val created =
            db.runTransaction { transaction ->
                  if (transaction.get(ref).exists()) false
                  else {
                    transaction.set(ref, profile.toNewDocument())
                    // A new profile has no username: it is claimed once the profile exists.
                    if (profile.accountType == AccountType.STUDENT)
                        transaction.set(
                            publicDocument(profile.uid),
                            profile.toPublicProfileDocument(username = null),
                        )
                    true
                  }
                }
                .await()
        if (created) CreateProfileResult.Created else CreateProfileResult.AlreadyExists
      }

  override suspend fun updateProfile(profile: UserProfile): UpdateProfileResult =
      reportFailures(UpdateProfileResult.NetworkError, UpdateProfileResult.UnexpectedError) {
        profile.requireValidBio()
        val ref = document(profile.uid)
        val updated =
            db.runTransaction { transaction ->
                  val stored = transaction.get(ref)
                  if (!stored.exists()) false
                  else {
                    transaction.update(
                        ref,
                        profile.toEditableFields() + profile.detailsUpdate(stored),
                    )
                    // Also writes one a student saved before #229 lacks. The stored username is
                    // kept: it isn't an editable field.
                    if (stored.getString("accountType") != AccountType.ASSOCIATION.value)
                        transaction.set(
                            publicDocument(profile.uid),
                            profile.toPublicProfileDocument(stored.get(USERNAME_FIELD) as? String),
                        )
                    true
                  }
                }
                .await()
        if (updated) UpdateProfileResult.Updated else UpdateProfileResult.NotFound
      }

  override suspend fun ensurePublicProfile(uid: String): EnsurePublicProfileResult =
      reportFailures(
          EnsurePublicProfileResult.NetworkError,
          EnsurePublicProfileResult.UnexpectedError,
      ) {
        val publicRef = publicDocument(uid)
        db.runTransaction<EnsurePublicProfileResult> { transaction ->
              val stored = transaction.get(document(uid))
              val profile = stored.toUserProfile()
              when {
                !stored.exists() -> EnsurePublicProfileResult.NotFound
                profile == null -> EnsurePublicProfileResult.UnexpectedError
                // Before reading publicProfiles, which the rules deny an association.
                profile.accountType == AccountType.ASSOCIATION ->
                    EnsurePublicProfileResult.NotNeeded
                transaction.get(publicRef).exists() -> EnsurePublicProfileResult.NotNeeded
                else -> {
                  transaction.set(publicRef, profile.toPublicProfileDocument(profile.username))
                  EnsurePublicProfileResult.Created
                }
              }
            }
            .await()
      }
}

/** The fields of a new `users/{uid}` document. `isAssociationVerified` is never written here. */
private fun UserProfile.toNewDocument(): Map<String, Any> =
    toEditableFields() +
        toProfileDetailsDocument() +
        mapOf(
            "uid" to uid,
            "email" to email,
            "accountType" to accountType.value,
            "createdAt" to FieldValue.serverTimestamp(),
        )

private fun UserProfile.toEditableFields(): Map<String, Any> =
    mapOf(
        "displayName" to displayName,
        "section" to section,
        "year" to year,
        "interests" to orderedInterests(interests),
    )

private fun UserProfile.detailsUpdate(stored: DocumentSnapshot): Map<String, Any> {
  val details = toProfileDetailsDocument()
  val removed =
      listOf(PROFILE_VISIBILITY_FIELD, BIO_FIELD)
          .filter { it !in details && stored.contains(it) }
          .associateWith { FieldValue.delete() }
  return details + removed
}

/**
 * The profile in this document, or `null` if a required field is missing, unknown, or stored with
 * the wrong type (Firestore throws for a field of another type). `isAssociationVerified` counts
 * only when it is the boolean `true`, like the rules' `== true`: an admin sets it by hand, so a
 * missing field or a typo such as the string "true" means not verified instead of an unreadable
 * profile.
 */
private fun DocumentSnapshot.toUserProfile(): UserProfile? =
    try {
      readUserProfile()
    } catch (e: RuntimeException) {
      null
    }

private fun DocumentSnapshot.readUserProfile(): UserProfile? {
  val accountType =
      AccountType.entries.firstOrNull { it.value == getString("accountType") } ?: return null
  return UserProfile(
      uid = getString("uid") ?: return null,
      email = getString("email") ?: return null,
      displayName = getString("displayName") ?: return null,
      section = getString("section") ?: return null,
      year = getString("year") ?: return null,
      accountType = accountType,
      isAssociationVerified = get("isAssociationVerified") == true,
      createdAt = getTimestamp("createdAt")?.toInstant(),
      interests = readInterests() ?: return null,
      // The rules only store a string; anything else reads as no username.
      username = get(USERNAME_FIELD) as? String,
      profileVisibility = readProfileVisibility(),
      bio = getString(BIO_FIELD),
  )
}

/**
 * The stored interests (#230): none for a profile saved before them, `null` (unreadable) if they
 * aren't a list of strings. A code no longer in [INTERESTS] is left out, so the profile stays
 * readable and is never saved back with it, which the rules would refuse.
 */
private fun DocumentSnapshot.readInterests(): Set<String>? {
  val stored = get("interests") ?: return emptySet()
  val codes = (stored as? List<*>)?.map { it as? String ?: return null } ?: return null
  return codes.filter { it in INTERESTS }.toSet()
}
