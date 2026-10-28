// Contributors: OpenAI Codex (shared Google attempt state and Welcome ViewModel for #107).
package com.polysocial.ui.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polysocial.model.auth.AuthRepository
import com.polysocial.model.auth.GoogleSignInResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** An attempt is locked through successful handoff; cancellations restore the original screen. */
data class GoogleSignInState(
    val loading: Boolean = false,
    val result: GoogleSignInResult? = null,
) {
  val busy: Boolean
    get() = loading || result is GoogleSignInResult.Success

  val error: GoogleSignInResult?
    get() = result?.takeUnless { it is GoogleSignInResult.Success }
}

/** Both entry screens run the same repository operation and translate cancellation identically. */
internal suspend fun googleSignIn(auth: AuthRepository, context: Context): GoogleSignInState {
  val result =
      try {
        auth.signInWithGoogle(context)
      } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        GoogleSignInResult.UnexpectedError
      }
  return GoogleSignInState(result = result.takeUnless { it == GoogleSignInResult.Cancelled })
}

/**
 * Owns the Welcome screen's Google attempt. Its coroutine holds the activity context until the
 * operation completes or is cancelled; the context is never stored in screen state or a field.
 */
@HiltViewModel
class WelcomeViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
  private val state = MutableStateFlow(GoogleSignInState())
  val uiState = state.asStateFlow()

  fun onGoogle(context: Context) {
    if (state.value.busy) return
    state.value = GoogleSignInState(loading = true)
    viewModelScope.launch { state.value = googleSignIn(auth, context) }
  }

  /** Drops rejected identity details when Back is tapped or the auth flow completes. */
  fun reset() {
    state.value = GoogleSignInState()
  }
}
