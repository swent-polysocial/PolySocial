// Contributors: OpenAI Codex (Credential Manager adapter tests for #106).
package com.polysocial.model.auth

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.NoCredentialException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.polysocial.R
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [34])
class AndroidGoogleCredentialSourceTest {
  private val context = mockk<Context>()
  private val manager = mockk<CredentialManager>()
  private val source = AndroidGoogleCredentialSource { manager }
  private val request = slot<GetCredentialRequest>()

  @Before
  fun setup() {
    every { context.packageName } returns "com.polysocial"
    every { context.applicationContext } returns context
    every { context.getString(R.string.default_web_client_id) } returns "test-web-client"
    mockkObject(GoogleIdTokenCredential.Companion)
  }

  @After
  fun cleanup() {
    unmockkAll()
  }

  @Test
  fun buttonRequestUsesWebClientAndGoogleEmail() = runTest {
    val bundle = Bundle()
    val google =
        mockk<GoogleIdTokenCredential> {
          every { email } returns "student@epfl.ch"
          every { displayName } returns "Demo Student"
          every { idToken } returns "test-token"
        }
    every { GoogleIdTokenCredential.createFrom(bundle) } returns google
    coEvery { manager.getCredential(context, capture(request)) } returns
        GetCredentialResponse(
            CustomCredential(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, bundle)
        )
    val result = source.get(context)
    assertEquals(GoogleAccount("student@epfl.ch", "Demo Student"), result.account)
    assertEquals("test-token", result.idToken)
    assertEquals(1, request.captured.credentialOptions.size)
    assertTrue(
        request.captured.credentialOptions.single()
            is com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
    )
  }

  @Test
  fun missingConfigurationFailsWithoutOpeningPicker() = runTest {
    every { context.getString(R.string.default_web_client_id) } returns ""
    assertTrue(runCatching { source.get(context) }.exceptionOrNull() is IllegalStateException)
  }

  @Test
  fun wrongCredentialTypeFails() = runTest {
    coEvery { manager.getCredential(context, any<GetCredentialRequest>()) } returns
        GetCredentialResponse(PasswordCredential("test", "test"))
    assertTrue(runCatching { source.get(context) }.exceptionOrNull() is IllegalStateException)
  }

  @Test
  fun unrelatedCustomCredentialFails() = runTest {
    coEvery { manager.getCredential(context, any<GetCredentialRequest>()) } returns
        GetCredentialResponse(CustomCredential("other", Bundle()))
    assertTrue(runCatching { source.get(context) }.exceptionOrNull() is IllegalStateException)
  }

  @Test
  fun noUsableGoogleCredentialIsRecoverable() = runTest {
    coEvery { manager.getCredential(context, any<GetCredentialRequest>()) } throws
        NoCredentialException()
    val failure = runCatching { source.get(context) }.exceptionOrNull()
    assertTrue(failure is IllegalStateException)
    assertTrue(failure?.cause is NoCredentialException)
  }

  @Test
  fun malformedGoogleCredentialFails() = runTest {
    val bundle = Bundle()
    every { GoogleIdTokenCredential.createFrom(bundle) } throws IllegalArgumentException("test")
    coEvery { manager.getCredential(context, any<GetCredentialRequest>()) } returns
        GetCredentialResponse(
            CustomCredential(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, bundle)
        )
    assertTrue(runCatching { source.get(context) }.exceptionOrNull() is IllegalArgumentException)
  }
}
