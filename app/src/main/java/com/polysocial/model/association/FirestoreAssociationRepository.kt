// Contributors: Claude (Firestore association repository, #126).
package com.polysocial.model.association

import com.google.firebase.FirebaseException
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await

/** Firestore collection holding the association profiles, one document per account UID. */
const val ASSOCIATIONS_COLLECTION = "associations"

/**
 * [AssociationRepository] backed by Firestore. Documents are converted to and from maps by hand, so
 * the stored field names are exactly the ones the Security Rules check (see CONTEXT.md).
 *
 * Writes run in a transaction, like `FirestoreUserProfileRepository`: it reads the document before
 * writing, which is how "create once" and "update only if it exists" are checked, and it needs the
 * server, so offline it fails instead of being queued like a plain write.
 */
class FirestoreAssociationRepository(private val database: () -> FirebaseFirestore) :
    AssociationRepository {
  constructor(db: FirebaseFirestore) : this({ db })

  private val db: FirebaseFirestore
    get() = database()

  private fun document(uid: String): DocumentReference =
      db.collection(ASSOCIATIONS_COLLECTION).document(uid)

  override suspend fun getAssociation(uid: String): AssociationResult =
      reportFailures(
          offline = AssociationResult.NetworkError,
          // A student may not read a pending association: it must look missing, not broken.
          denied = AssociationResult.NotFound,
          failed = AssociationResult.UnexpectedError,
      ) {
        val snapshot = document(uid).get().await()
        if (!snapshot.exists()) AssociationResult.NotFound
        else
            snapshot.toAssociation()?.let { AssociationResult.Found(it) }
                ?: AssociationResult.UnexpectedError
      }

  override suspend fun createAssociation(association: Association): CreateAssociationResult =
      reportFailures(
          offline = CreateAssociationResult.NetworkError,
          denied = CreateAssociationResult.UnexpectedError,
          failed = CreateAssociationResult.UnexpectedError,
      ) {
        val ref = document(association.uid)
        val created =
            db.runTransaction { transaction ->
                  if (transaction.get(ref).exists()) false
                  else {
                    transaction.set(ref, association.toNewDocument())
                    true
                  }
                }
                .await()
        if (created) CreateAssociationResult.Created else CreateAssociationResult.AlreadyExists
      }

  override suspend fun updateAssociation(association: Association): UpdateAssociationResult =
      reportFailures(
          offline = UpdateAssociationResult.NetworkError,
          denied = UpdateAssociationResult.UnexpectedError,
          failed = UpdateAssociationResult.UnexpectedError,
      ) {
        val ref = document(association.uid)
        val updated =
            db.runTransaction { transaction ->
                  if (!transaction.get(ref).exists()) false
                  else {
                    transaction.update(ref, association.toEditedFields())
                    true
                  }
                }
                .await()
        if (updated) UpdateAssociationResult.Updated else UpdateAssociationResult.NotFound
      }
}

/**
 * Runs [call] and turns its failures into results, so the caller never hangs: offline becomes
 * [offline], a request the Security Rules deny becomes [denied], and any other Firebase failure, a
 * Firebase task cancelled while the caller is still running, or any other exception (for example a
 * Firestore client that was shut down) becomes [failed]. Cancelling the caller's coroutine still
 * cancels it.
 */
private suspend fun <T> reportFailures(offline: T, denied: T, failed: T, call: suspend () -> T): T =
    try {
      call()
    } catch (e: FirebaseFirestoreException) {
      when (e.code) {
        FirebaseFirestoreException.Code.UNAVAILABLE -> offline
        FirebaseFirestoreException.Code.PERMISSION_DENIED -> denied
        else -> failed
      }
    } catch (e: FirebaseException) {
      failed
    } catch (e: CancellationException) {
      currentCoroutineContext().ensureActive()
      failed
    } catch (e: Exception) {
      failed
    }

/** The fields of a new `associations/{uid}` document; `website` is left out when there is none. */
private fun Association.toNewDocument(): Map<String, Any> =
    editableFields() +
        listOfNotNull(website?.let { "website" to it }) +
        ("createdAt" to FieldValue.serverTimestamp())

/** The edited fields; a removed website deletes the stored field instead of storing `null`. */
private fun Association.toEditedFields(): Map<String, Any> =
    editableFields() + ("website" to (website ?: FieldValue.delete()))

private fun Association.editableFields(): Map<String, Any> =
    mapOf("name" to name, "type" to type.name, "about" to about)

/**
 * The association in this document, or `null` if a required field is missing or unknown. A field
 * stored with the wrong type makes Firestore throw, which [reportFailures] reports as an unexpected
 * error too. The UID is the document ID.
 */
private fun DocumentSnapshot.toAssociation(): Association? {
  val type = AssociationType.entries.firstOrNull { it.name == getString("type") } ?: return null
  return Association(
      uid = id,
      name = getString("name") ?: return null,
      type = type,
      about = getString("about") ?: return null,
      website = getString("website"),
      createdAt = getTimestamp("createdAt")?.toInstant(),
  )
}
