// Contributors: Claude (test fake for association profiles, #126).
package com.polysocial.model.association

import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory [AssociationRepository] for tests of the screens built on it (#125, #127, #128, #134).
 * Set the next result of each operation, and check how often each one was called and with what.
 * Set [gate] to keep every operation suspended until the test completes it, to observe a loading
 * or saving state.
 */
class FakeAssociationRepository(
    var getAssociationResult: AssociationResult = AssociationResult.NotFound,
) : AssociationRepository {
  var createAssociationResult: CreateAssociationResult = CreateAssociationResult.Created
  var updateAssociationResult: UpdateAssociationResult = UpdateAssociationResult.Updated

  var gate: CompletableDeferred<Unit>? = null

  /** The UIDs passed to [getAssociation], in call order. */
  val requestedUids = mutableListOf<String>()

  /** The associations passed to [createAssociation], in call order. */
  val createdAssociations = mutableListOf<Association>()

  /** The associations passed to [updateAssociation], in call order. */
  val updatedAssociations = mutableListOf<Association>()

  override suspend fun getAssociation(uid: String): AssociationResult {
    requestedUids += uid
    gate?.await()
    return getAssociationResult
  }

  override suspend fun createAssociation(association: Association): CreateAssociationResult {
    createdAssociations += association
    gate?.await()
    return createAssociationResult
  }

  override suspend fun updateAssociation(association: Association): UpdateAssociationResult {
    updatedAssociations += association
    gate?.await()
    return updateAssociationResult
  }
}
