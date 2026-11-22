// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests for the username fake, #448).
package com.polysocial.model.user

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [FakeUsernameRepository] follows the [UsernameRepository] contracts the screens rely on. */
class FakeUsernameRepositoryTest {
  private val repository = FakeUsernameRepository(currentUid = "me")

  @Test
  fun claimingAFreeName_givesItToTheStudent() = runTest {
    assertEquals(SetUsernameResult.Saved, repository.setUsername("franek"))
    assertEquals(UsernameLookupResult.Found("me"), repository.lookUp("franek"))
  }

  @Test
  fun renaming_freesThePreviousName() = runTest {
    repository.setUsername("franek")

    assertEquals(SetUsernameResult.Saved, repository.setUsername("franek2"))

    assertEquals(UsernameLookupResult.NotFound, repository.lookUp("franek"))
    assertEquals(UsernameLookupResult.Found("me"), repository.lookUp("franek2"))
  }

  @Test
  fun settingTheNameAlreadyHeld_isSaved() = runTest {
    repository.setUsername("franek")

    assertEquals(SetUsernameResult.Saved, repository.setUsername("franek"))
    assertEquals(mapOf("franek" to "me"), repository.names)
  }

  @Test
  fun aNameAnotherStudentHolds_isTaken() = runTest {
    repository.names["lea"] = "lea-uid"
    repository.setUsername("franek")

    assertEquals(SetUsernameResult.Taken, repository.setUsername("lea"))

    assertEquals(mapOf("lea" to "lea-uid", "franek" to "me"), repository.names)
  }

  @Test
  fun aNameTheFormatRefuses_isInvalid_beforeAnyFailure() = runTest {
    repository.setFailure = SetUsernameResult.NetworkError

    assertEquals(SetUsernameResult.Invalid(UsernameError.FORMAT), repository.setUsername("9lives"))
    assertEquals(SetUsernameResult.Invalid(UsernameError.RESERVED), repository.setUsername("admin"))
    assertTrue(repository.names.isEmpty())
  }

  @Test
  fun aFailedWrite_writesNothing() = runTest {
    repository.setFailure = SetUsernameResult.NetworkError

    assertEquals(SetUsernameResult.NetworkError, repository.setUsername("franek"))
    assertTrue(repository.names.isEmpty())
  }

  @Test
  fun nobodySignedIn_isUnexpected() = runTest {
    repository.currentUid = null

    assertEquals(SetUsernameResult.UnexpectedError, repository.setUsername("franek"))
    assertTrue(repository.names.isEmpty())
  }

  @Test
  fun lookingUpAFreeOrInvalidName_isNotFound() = runTest {
    repository.lookUpFailure = UsernameLookupResult.NetworkError

    assertEquals(UsernameLookupResult.NotFound, repository.lookUp("admin"))
    assertEquals(UsernameLookupResult.NotFound, repository.lookUp("@franek"))

    repository.lookUpFailure = null
    assertEquals(UsernameLookupResult.NotFound, repository.lookUp("franek"))
  }

  @Test
  fun aFailedLookUp_returnsItsFailure() = runTest {
    repository.names["lea"] = "lea-uid"
    repository.lookUpFailure = UsernameLookupResult.NetworkError

    assertEquals(UsernameLookupResult.NetworkError, repository.lookUp("lea"))
    assertEquals(listOf("lea"), repository.lookedUp)
  }
}
