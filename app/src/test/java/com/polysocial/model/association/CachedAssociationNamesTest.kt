// Contributors: Claude (wrote these tests, #128; additional tests from a test audit and a
// review, #128).
package com.polysocial.model.association

import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedAssociationNamesTest {
  private val associations =
      FakeAssociationRepository(AssociationResult.Found(club("club1", "Test Club")))
  private val auth = FakeAuthRepository(AuthUser("student1", "test.student@epfl.ch", true))
  private val names = CachedAssociationNames(associations, auth)

  private suspend fun current() = names.names.first()

  @Test
  fun loadingAnAssociation_makesItsNameKnown() = runTest {
    assertEquals(emptyMap<String, String>(), current())

    names.load("club1")

    assertEquals(mapOf("club1" to "Test Club"), current())
    assertEquals(listOf("club1"), associations.requestedUids)
  }

  @Test
  fun aKnownName_isNeverReadAgain() = runTest {
    names.load("club1")
    names.load("club1")
    names.load("club1")

    assertEquals(listOf("club1"), associations.requestedUids)
    assertEquals(mapOf("club1" to "Test Club"), current())
  }

  @Test
  fun severalLoadsAtOnce_readTheAssociationOnce() = runTest {
    associations.gate = CompletableDeferred()
    val loads = List(3) { launch(start = CoroutineStart.UNDISPATCHED) { names.load("club1") } }
    advanceUntilIdle()

    associations.gate!!.complete(Unit)
    loads.forEach { it.join() }

    assertEquals(listOf("club1"), associations.requestedUids)
    assertEquals(mapOf("club1" to "Test Club"), current())
  }

  @Test
  fun eachAssociation_isReadOnceAndKeptSeparately() = runTest {
    val answers =
        mapOf(
            "club1" to AssociationResult.Found(club("club1", "Test Club")),
            "club2" to AssociationResult.Found(club("club2", "Ski Club")),
        )
    val perUid = PerUidAssociations(answers)
    val names = CachedAssociationNames(perUid, auth)

    names.load("club1")
    names.load("club2")
    names.load("club1")

    assertEquals(mapOf("club1" to "Test Club", "club2" to "Ski Club"), names.names.first())
    assertEquals(listOf("club1", "club2"), perUid.requestedUids)
  }

  @Test
  fun aNameThatCantBeRead_staysUnknown_andIsTriedAgainLater() = runTest {
    for (failure in
        listOf(
            AssociationResult.NotFound,
            AssociationResult.NetworkError,
            AssociationResult.UnexpectedError,
        )) {
      associations.getAssociationResult = failure
      associations.requestedUids.clear()

      names.load("club1")
      assertEquals("After $failure", emptyMap<String, String>(), current())

      names.load("club1")
      assertEquals("After $failure, retried", listOf("club1", "club1"), associations.requestedUids)
    }

    associations.getAssociationResult = AssociationResult.Found(club("club1", "Test Club"))
    names.load("club1")
    assertEquals(mapOf("club1" to "Test Club"), current())
  }

  @Test
  fun aBlankName_staysUnknown() = runTest {
    associations.getAssociationResult = AssociationResult.Found(club("club1", "   "))

    names.load("club1")

    assertEquals(emptyMap<String, String>(), current())
  }

  @Test
  fun anotherAccount_seesNoneOfThePreviousAccountsNames_andReadsThemAgain() = runTest {
    names.load("club1")
    assertEquals(mapOf("club1" to "Test Club"), current())

    auth.user = AuthUser("student2", "other.student@epfl.ch", true)

    assertEquals(emptyMap<String, String>(), current())
    names.load("club1")
    assertEquals(listOf("club1", "club1"), associations.requestedUids)
    assertEquals(mapOf("club1" to "Test Club"), current())
  }

  @Test
  fun signingOut_hidesTheNames() = runTest {
    names.load("club1")

    auth.user = null

    assertEquals(emptyMap<String, String>(), current())
  }

  @Test
  fun aCancelledLoad_letsTheNextOneRead() = runTest {
    associations.gate = CompletableDeferred()
    val first = launch(start = CoroutineStart.UNDISPATCHED) { names.load("club1") }
    first.cancel()
    first.join()
    associations.gate = null

    names.load("club1")

    assertEquals(mapOf("club1" to "Test Club"), current())
    assertTrue(first.isCancelled)
  }

  @Test
  fun theNamesFlow_emitsWhenANameArrives() = runTest {
    val seen = mutableListOf<Map<String, String>>()
    val collector = launch { names.names.collect { seen += it } }
    advanceUntilIdle()

    names.load("club1")
    advanceUntilIdle()
    collector.cancel()

    assertEquals(listOf(emptyMap(), mapOf("club1" to "Test Club")), seen)
  }

  @Test
  fun aSlowAssociation_doesntHoldUpTheOthers() = runTest {
    val gated = GatedAssociations()
    val names = CachedAssociationNames(gated, auth)
    val slow = launch(start = CoroutineStart.UNDISPATCHED) { names.load("club1") }

    names.load("club2")

    assertEquals(mapOf("club2" to "Ski Club"), names.names.first())
    gated.club1Gate.complete(Unit)
    slow.join()
    assertEquals(mapOf("club1" to "Test Club", "club2" to "Ski Club"), names.names.first())
  }

  @Test
  fun aNameReadForThePreviousAccount_isNotShownToTheNextOne() = runTest {
    val gated = GatedAssociations()
    val names = CachedAssociationNames(gated, auth)
    val previous = launch(start = CoroutineStart.UNDISPATCHED) { names.load("club1") }

    auth.user = AuthUser("student2", "other.student@epfl.ch", true)
    gated.club1Gate.complete(Unit)
    previous.join()

    assertEquals(emptyMap<String, String>(), names.names.first())
  }

  @Test
  fun aLoadStillRunningForThePreviousAccount_keepsTheNextAccountsNames() = runTest {
    val gated = GatedAssociations()
    val names = CachedAssociationNames(gated, auth)
    val previous = launch(start = CoroutineStart.UNDISPATCHED) { names.load("club1") }

    auth.user = AuthUser("student2", "other.student@epfl.ch", true)
    names.load("club2")
    gated.club1Gate.complete(Unit)
    previous.join()

    assertEquals(mapOf("club2" to "Ski Club"), names.names.first())
  }

  /** An [AssociationRepository] where "club1" answers only once [club1Gate] completes. */
  private class GatedAssociations : AssociationRepository by FakeAssociationRepository() {
    val club1Gate = CompletableDeferred<Unit>()

    override suspend fun getAssociation(uid: String): AssociationResult =
        when (uid) {
          "club1" -> {
            club1Gate.await()
            AssociationResult.Found(club("club1", "Test Club"))
          }
          "club2" -> AssociationResult.Found(club("club2", "Ski Club"))
          else -> AssociationResult.NotFound
        }
  }

  /** An [AssociationRepository] answering each UID from [answers] (missing ones are NotFound). */
  private class PerUidAssociations(private val answers: Map<String, AssociationResult>) :
      AssociationRepository by FakeAssociationRepository() {
    val requestedUids = mutableListOf<String>()

    override suspend fun getAssociation(uid: String): AssociationResult {
      requestedUids += uid
      return answers[uid] ?: AssociationResult.NotFound
    }
  }

  private companion object {
    fun club(uid: String, name: String) =
        Association(
            uid = uid,
            name = name,
            type = AssociationType.CLUB,
            about = "A fictional club.",
        )
  }
}
