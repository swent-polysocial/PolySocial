// Contributors: Claude (association model and repository contract, #126).
package com.polysocial.model.association

import java.time.Instant

/** The kinds of organization in the Figma "Association setup" frame, stored by [name]. */
enum class AssociationType {
  ASSOCIATION,
  COMMISSION,
  CLUB,
  EPFL_UNIT,
}

/**
 * The public profile of an association, `associations/{uid}`, where [uid] is the association
 * account's Firebase Auth UID (the document ID), so each account has exactly one association.
 * Students see these fields once an admin has verified the association, so nothing private is
 * stored here: the email and the admin verification stay in the owner-only `users/{uid}`.
 *
 * The Security Rules accept a [name] of 1 to 60 characters and an [about] of 1 to 1000, neither
 * blank, and an optional [website] of at most 200 characters, starting with `https://` and with no
 * whitespace (#126). [createdAt] is set by the server when the association is created, so it is
 * `null` before that.
 */
data class Association(
    val uid: String,
    val name: String,
    val type: AssociationType,
    val about: String,
    val website: String? = null,
    val createdAt: Instant? = null,
)

/** Outcome of [AssociationRepository.getAssociation]. */
sealed interface AssociationResult {
  data class Found(val association: Association) : AssociationResult

  /**
   * There is no association for this UID, or the reader may not see it: an association an admin
   * hasn't verified yet looks missing to students.
   */
  data object NotFound : AssociationResult

  data object NetworkError : AssociationResult

  /** Any other failure, including a stored document with a missing or wrong-typed field. */
  data object UnexpectedError : AssociationResult
}

/** Outcome of [AssociationRepository.createAssociation]. */
sealed interface CreateAssociationResult {
  data object Created : CreateAssociationResult

  /**
   * An association already exists for this UID; it was left unchanged. A retry after a
   * [NetworkError] can land here when the first write was saved but its answer was lost: the
   * association exists, so the caller can go on (and update it if the form changed since).
   */
  data object AlreadyExists : CreateAssociationResult

  /** The device is offline. Nothing was written or queued, so the user can try again. */
  data object NetworkError : CreateAssociationResult

  /** Any other failure, for example a write the Security Rules deny. */
  data object UnexpectedError : CreateAssociationResult
}

/** Outcome of [AssociationRepository.updateAssociation]. */
sealed interface UpdateAssociationResult {
  data object Updated : UpdateAssociationResult

  /** No association exists for this UID yet; create it with `createAssociation`. */
  data object NotFound : UpdateAssociationResult

  /** The device is offline. Nothing was written or queued, so the user can try again. */
  data object NetworkError : UpdateAssociationResult

  /**
   * Any other failure, for example a write the Security Rules deny, such as a new name or type for
   * an association an admin has already verified.
   */
  data object UnexpectedError : UpdateAssociationResult
}

/**
 * Reads and writes association profiles in `associations/{uid}` (#126). The document ID is always
 * the association account's UID. The Security Rules let the owner read and write its own document,
 * pending or verified, and students read an association once an admin has verified it.
 */
interface AssociationRepository {
  /** The association of [uid], or [AssociationResult.NotFound] if it is missing or not readable. */
  suspend fun getAssociation(uid: String): AssociationResult

  /**
   * Creates `associations/{association.uid}` once: if it already exists it is left unchanged and
   * [CreateAssociationResult.AlreadyExists] is returned. The server sets [Association.createdAt],
   * whatever [association] holds. Offline, nothing is queued and
   * [CreateAssociationResult.NetworkError] is returned, so an association is never left
   * half-created.
   *
   * The owner's `users/{uid}` must already be saved as an association's (accountType
   * "association"), in an earlier write: the Security Rules read it as it was before this write, so
   * writing both in one batch or transaction is refused.
   */
  suspend fun createAssociation(association: Association): CreateAssociationResult

  /**
   * Saves [Association.name], [Association.type], [Association.about] and [Association.website] in
   * the existing `associations/{association.uid}`; a `null` website removes the stored one.
   * [Association.createdAt] never changes. Offline, nothing is queued and
   * [UpdateAssociationResult.NetworkError] is returned.
   */
  suspend fun updateAssociation(association: Association): UpdateAssociationResult
}
