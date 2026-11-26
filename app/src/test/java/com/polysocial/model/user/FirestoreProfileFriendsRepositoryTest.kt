// Contributors: OpenAI Codex (GPT-6.2 Sol, high; #450 under Lucas Pinto Ricardo's supervision and
// approval).
package com.polysocial.model.user

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import com.polysocial.model.auth.AuthUser
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.firebase.ProfileFriendsCache
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirestoreProfileFriendsRepositoryTest {
  private val db = mockk<FirebaseFirestore>()
  private val collection = mockk<CollectionReference>()
  private val profiles = mockk<CollectionReference>()
  private val profile = PublicProfile("owner", "Test Owner", "IN", "BA3")
  private val auth = FakeAuthRepository(AuthUser("viewer", "student@epfl.ch", true))
  private val context = ApplicationProvider.getApplicationContext<Context>()

  private fun repository(io: CoroutineDispatcher) =
      FirestoreProfileFriendsRepository({ db }, auth, ProfileFriendsCache(context), io)

  init {
    val owner = mockk<DocumentReference>()
    every { db.collection("publicProfiles") } returns profiles
    every { profiles.document("owner") } returns owner
    every { owner.collection("friends") } returns collection
  }

  @Before
  fun resetCache() {
    ProfileFriendsCache(context).clear()
  }

  private fun snapshot(vararg ids: String) =
      mockk<QuerySnapshot> {
        every { documents } returns
            ids.map { uid -> mockk<DocumentSnapshot> { every { id } returns uid } }
      }

  @Test
  fun aConfirmedEmptyListSurvivesReopeningButAnUntouchedOneIsUnknown() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val repository = repository(dispatcher)
    every { collection.get(Source.CACHE) } returns Tasks.forResult(snapshot())
    assertEquals(ProfileFriendsResult.Offline, repository.read(profile, online = false))

    every { collection.get(Source.SERVER) } returns Tasks.forResult(snapshot())
    assertEquals(ProfileFriendsResult.Found(emptyList()), repository.read(profile, online = true))

    assertEquals(
        ProfileFriendsResult.Found(emptyList(), fromCache = true),
        repository(dispatcher).read(profile, online = false),
    )
  }

  @Test
  fun onlyTheConfirmedMembershipForThisViewerAndVisibilityCanSupplyAnOfflineCount() = runTest {
    val repository = repository(StandardTestDispatcher(testScheduler))
    every { collection.get(Source.SERVER) } returns Tasks.forResult(snapshot("u1", "u2"))
    assertEquals(ProfileFriendsResult.Found(listOf("u1", "u2")), repository.read(profile, true))

    every { collection.get(Source.CACHE) } returns Tasks.forResult(snapshot("u2", "u1"))
    assertEquals(
        ProfileFriendsResult.Found(listOf("u2", "u1"), true),
        repository.read(profile, false),
    )
    assertEquals(
        ProfileFriendsResult.Offline,
        repository.read(profile.copy(profileVisibility = ProfileVisibility.PUBLIC), false),
    )
    auth.user = AuthUser("different", "other@epfl.ch", true)
    assertEquals(ProfileFriendsResult.Offline, repository.read(profile, false))
    auth.user = AuthUser("viewer", "student@epfl.ch", true)

    every { collection.get(Source.CACHE) } returns Tasks.forResult(snapshot("u1"))
    assertEquals(ProfileFriendsResult.Offline, repository.read(profile, false))
    every { collection.get(Source.CACHE) } returns Tasks.forResult(snapshot("u1", "u3"))
    assertEquals(ProfileFriendsResult.Offline, repository.read(profile, false))
  }

  @Test
  fun anUnavailableOrStalledServerUsesTheCompleteCacheButDenialInvalidatesIt() = runTest {
    val repository = repository(StandardTestDispatcher(testScheduler))
    every { collection.get(Source.SERVER) } returns Tasks.forResult(snapshot("u1"))
    every { collection.get(Source.CACHE) } returns Tasks.forResult(snapshot("u1"))
    assertEquals(ProfileFriendsResult.Found(listOf("u1")), repository.read(profile, true))
    val cached = ProfileFriendsResult.Found(listOf("u1"), fromCache = true)

    every { collection.get(Source.SERVER) } returns
        Tasks.forException(
            FirebaseFirestoreException("offline", FirebaseFirestoreException.Code.UNAVAILABLE)
        )
    assertEquals(cached, repository.read(profile, true))
    every { collection.get(Source.SERVER) } returns TaskCompletionSource<QuerySnapshot>().task
    assertEquals(cached, repository.read(profile, true))

    every { collection.get(Source.SERVER) } returns
        Tasks.forException(
            FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
        )
    assertEquals(ProfileFriendsResult.Denied, repository.read(profile, true))
    assertEquals(ProfileFriendsResult.Offline, repository.read(profile, false))
  }

  @Test
  fun unexpectedReadFailureAndSignedOutAreErrors() = runTest {
    val repository = repository(StandardTestDispatcher(testScheduler))
    every { collection.get(Source.SERVER) } returns
        Tasks.forException(
            FirebaseFirestoreException("failed", FirebaseFirestoreException.Code.INTERNAL)
        )
    assertEquals(ProfileFriendsResult.Error, repository.read(profile, true))

    auth.user = null
    assertEquals(ProfileFriendsResult.Error, repository.read(profile, true))
    verify(exactly = 1) { collection.get(Source.SERVER) }
    verify(exactly = 0) { collection.get(Source.CACHE) }
  }

  @Test
  fun wiredRepositoryReadsTheReplacementFirestoreSession() = runTest {
    var current = mockk<FirebaseFirestore>()
    val wired =
        ProfileFriendsModule.profileFriendsRepository(
            Provider { current },
            auth,
            ProfileFriendsCache(context),
        )
    current = db
    every { collection.get(Source.SERVER) } returns Tasks.forResult(snapshot("u1"))

    assertEquals(ProfileFriendsResult.Found(listOf("u1")), wired.read(profile, true))
  }

  @Test
  fun clearedCacheCannotCertifyEvenAnEmptyList() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val repository = repository(dispatcher)
    every { collection.get(Source.SERVER) } returns Tasks.forResult(snapshot())
    every { collection.get(Source.CACHE) } returns Tasks.forResult(snapshot())
    assertEquals(ProfileFriendsResult.Found(emptyList()), repository.read(profile, true))

    ProfileFriendsCache(context).clear()

    assertEquals(ProfileFriendsResult.Offline, repository(dispatcher).read(profile, false))
  }
}
