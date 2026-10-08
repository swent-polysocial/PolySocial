// Contributors: OpenAI Codex (GPT-6.1 Sol, medium; translated the Figma sign-up form to Compose
// Foundation, reusing the shared app theme; connected login and added the official Google
// placeholder button; connected the welcome entry screen and app-start/profile routing;
// preserved submission handoffs on Back and aligned form hints, loading and recovery layout).
package com.polysocial.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.model.auth.*
import com.polysocial.ui.login.LoginScreen
import com.polysocial.ui.login.LoginViewModel
import com.polysocial.ui.navigation.AppShell
import com.polysocial.ui.start.AppStartViewModel
import com.polysocial.ui.start.StartDestination
import com.polysocial.ui.theme.Disabled
import com.polysocial.ui.theme.Success
import kotlinx.coroutines.launch

/** Stable tags for form inputs, validation feedback and handoff actions. */
object SignUpTags {
  const val Screen = "sign_up_screen"
  const val Google = "sign_up_google"
  const val Submit = "sign_up_submit"
  const val LoadingSpinner = "sign_up_loading_spinner"
  const val BackendError = "sign_up_backend_error"
  const val LogInInstead = "sign_up_log_in_instead"
  const val LogIn = "sign_up_log_in"
  const val Back = "sign_up_back"
  const val VerifyEmailDestination = "verify_email_destination"
  const val LengthRule = "sign_up_length_rule"
  const val NumberRule = "sign_up_number_rule"

  fun input(field: SignUpField) = "sign_up_${field.name}"

  fun error(field: SignUpField) = "sign_up_${field.name}_error"

  fun visibility(field: SignUpField) = "sign_up_${field.name}_visibility"
}

@Composable
private fun SignUpSpinner() {
  val transition = rememberInfiniteTransition(label = "Sign-up loading")
  val rotation by
      transition.animateFloat(
          initialValue = 0f,
          targetValue = 360f,
          animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
          label = "Spinner rotation",
      )
  Image(
      painterResource(R.drawable.signup_spinner),
      contentDescription = null,
      colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimary),
      modifier =
          Modifier.size(18.dp).rotate(rotation).progressSemantics().semantics {
            testTag = SignUpTags.LoadingSpinner
          },
  )
}

private enum class AuthDestination {
  Loading,
  Error,
  ProfileSetup,
  Welcome,
  SignUp,
  VerifyEmail,
  LogIn,
  SignedIn,
}

/** Connects auth screens to session/profile routing; #31 and #34 supply the remaining screens. */
@Composable
fun AuthFlow(
    viewModel: SignUpViewModel,
    onExit: () -> Unit,
    loginViewModel: LoginViewModel = hiltViewModel(),
    startViewModel: AppStartViewModel = hiltViewModel(),
) {
  var destination by rememberSaveable { mutableStateOf(AuthDestination.Loading) }
  var startupApplied by rememberSaveable { mutableStateOf(false) }
  val startDestination by startViewModel.destination.collectAsStateWithLifecycle()
  LaunchedEffect(startDestination) {
    // Preserve the active signed-out form on recreation; fresh launches resolve the session.
    if (
        !startupApplied ||
            startDestination != StartDestination.Login ||
            destination == AuthDestination.Loading
    ) {
      destination =
          when (startDestination) {
            StartDestination.Loading -> AuthDestination.Loading
            StartDestination.Login -> AuthDestination.Welcome
            StartDestination.VerifyEmail -> AuthDestination.VerifyEmail
            StartDestination.ProfileSetup -> AuthDestination.ProfileSetup
            StartDestination.Main -> AuthDestination.SignedIn
            StartDestination.Error -> AuthDestination.Error
          }
      if (startDestination != StartDestination.Loading) startupApplied = true
    }
  }
  var loginOrigin by rememberSaveable { mutableStateOf(AuthDestination.Welcome) }
  BackHandler(destination != AuthDestination.Welcome && destination != AuthDestination.SignedIn) {
    when (destination) {
      AuthDestination.VerifyEmail,
      AuthDestination.ProfileSetup,
      AuthDestination.Loading,
      AuthDestination.Error -> onExit()
      AuthDestination.LogIn -> destination = loginOrigin
      else -> destination = AuthDestination.Welcome
    }
  }
  when (destination) {
    AuthDestination.Loading ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator(Modifier.semantics { testTag = "auth_start_loading" })
        }
    AuthDestination.Error ->
        Column(Modifier.safeDrawingPadding()) {
          Label(
              stringResource(R.string.auth_start_error),
              Modifier.semantics { testTag = "auth_start_error" },
          )
          Button(
              onClick = startViewModel::refresh,
              modifier = Modifier.semantics { testTag = "auth_start_retry" },
          ) {
            Label(stringResource(R.string.auth_start_retry))
          }
        }
    AuthDestination.ProfileSetup ->
        Label(
            stringResource(R.string.auth_profile_setup),
            Modifier.safeDrawingPadding().semantics { testTag = "auth_profile_setup" },
        )
    AuthDestination.Welcome ->
        WelcomeScreen(
            onSignUp = { destination = AuthDestination.SignUp },
            onLogIn = {
              loginOrigin = AuthDestination.Welcome
              destination = AuthDestination.LogIn
            },
        )
    AuthDestination.SignUp ->
        SignUpRoute(
            viewModel,
            onSignedUp = { destination = AuthDestination.VerifyEmail },
            onLogIn = {
              loginOrigin = AuthDestination.SignUp
              destination = AuthDestination.LogIn
            },
            onBack = { destination = AuthDestination.Welcome },
        )
    AuthDestination.VerifyEmail ->
        Label(
            stringResource(R.string.verify_email_title),
            Modifier.safeDrawingPadding().semantics { testTag = SignUpTags.VerifyEmailDestination },
        )
    AuthDestination.LogIn ->
        LoginScreen(
            onBack = { destination = loginOrigin },
            onLoggedIn = startViewModel::refresh,
            onNeedsVerification = { destination = AuthDestination.VerifyEmail },
            onCreateAccount = { destination = AuthDestination.SignUp },
            viewModel = loginViewModel,
        )
    AuthDestination.SignedIn -> AppShell()
  }
}

@Composable
fun SignUpRoute(
    viewModel: SignUpViewModel,
    onSignedUp: (AuthUser) -> Unit,
    onLogIn: () -> Unit,
    onBack: () -> Unit,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  // Keep the success effect in composition while the request and handoff finish.
  BackHandler(state.status is SignUpStatus.Loading || state.status is SignUpStatus.SignedUp) {}
  val signedUp = state.status as? SignUpStatus.SignedUp
  val latestOnSignedUp by rememberUpdatedState(onSignedUp)
  LaunchedEffect(signedUp) { signedUp?.let { latestOnSignedUp(it.user) } }
  SignUpScreen(state, viewModel::update, viewModel::markTouched, viewModel::signUp, onLogIn, onBack)
}

@Composable
fun SignUpScreen(
    state: SignUpUiState,
    onChange: (SignUpField, String) -> Unit,
    onBlur: (SignUpField) -> Unit,
    onSubmit: () -> Unit,
    onLogIn: () -> Unit,
    onBack: () -> Unit,
) {
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val notAvailable = stringResource(R.string.not_available_yet)
  Box(Modifier.fillMaxSize()) {
    val editingEnabled =
        state.status !is SignUpStatus.Loading && state.status !is SignUpStatus.SignedUp
    val backendError = (state.status as? SignUpStatus.Error)?.reason
    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .semantics { testTag = SignUpTags.Screen }
    ) {
      Box(
          Modifier.padding(top = 10.dp)
              .size(48.dp)
              .clip(MaterialTheme.shapes.large)
              .clickable(enabled = editingEnabled, role = Role.Button, onClick = onBack)
              .semantics {
                testTag = SignUpTags.Back
                contentDescription = ""
              },
          contentAlignment = Alignment.CenterStart,
      ) {
        Image(
            painterResource(R.drawable.signup_back),
            stringResource(R.string.signup_back),
            Modifier.size(22.dp),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
        )
      }
      Spacer(Modifier.height(10.dp))
      Label(stringResource(R.string.signup_title), style = MaterialTheme.typography.headlineMedium)
      Spacer(Modifier.height(8.dp))
      Label(
          stringResource(R.string.signup_description),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.widthIn(max = 300.dp),
      )
      Spacer(Modifier.height(18.dp))
      Column(Modifier.alpha(if (state.status is SignUpStatus.Loading) 0.5f else 1f)) {
        SignUpField.entries.forEach { field ->
          val fieldError = state.visibleError(field)
          val isEmailFailure =
              field == SignUpField.Email &&
                  backendError in listOf(SignUpResult.AlreadyInUse, SignUpResult.InvalidDomain)
          FormField(
              field,
              state.form,
              fieldError != null || isEmailFailure,
              editingEnabled,
              { onChange(field, it) },
              { onBlur(field) },
          )
          if (fieldError != null) {
            ErrorLabel(stringResource(fieldError.message()), SignUpTags.error(field))
          }
          if (field == SignUpField.Email) {
            when {
              isEmailFailure -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  ErrorLabel(
                      stringResource(backendError!!.message()),
                      SignUpTags.BackendError,
                      Modifier.weight(1f),
                  )
                  if (backendError == SignUpResult.AlreadyInUse) {
                    Link(
                        stringResource(R.string.signup_log_in_instead),
                        SignUpTags.LogInInstead,
                        onLogIn,
                    )
                  }
                }
              }
              state.form.email.isNotEmpty() && state.form.error(SignUpField.Email) == null ->
                  Rule(stringResource(R.string.signup_epfl_address), true, "sign_up_epfl_address")
            }
          }
          if (field == SignUpField.Password) {
            Spacer(Modifier.height(8.dp))
            Rule(
                stringResource(R.string.signup_minimum_length),
                state.form.hasMinimumLength,
                SignUpTags.LengthRule,
            )
            Rule(
                stringResource(R.string.signup_contains_number),
                state.form.hasNumber,
                SignUpTags.NumberRule,
            )
          }
          Spacer(Modifier.height(14.dp))
        }
      }
      if (
          backendError != null &&
              backendError !in listOf(SignUpResult.AlreadyInUse, SignUpResult.InvalidDomain)
      ) {
        ErrorLabel(stringResource(backendError.message()), SignUpTags.BackendError)
        Spacer(Modifier.height(12.dp))
      }
      Spacer(Modifier.height(10.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outline))
        Label(
            stringResource(R.string.signup_or),
            Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outline))
      }
      Spacer(Modifier.height(16.dp))
      GoogleSignInButton(
          tag = SignUpTags.Google,
          enabled = editingEnabled,
          onClick = { scope.launch { snackbarHostState.showSnackbar(notAvailable) } },
      )
      Spacer(Modifier.height(16.dp))
      val submitLabel =
          stringResource(
              if (state.status is SignUpStatus.Loading) R.string.signup_creating
              else R.string.signup_create
          )
      Box(
          Modifier.fillMaxWidth()
              .heightIn(min = 52.dp)
              .clip(MaterialTheme.shapes.large)
              .background(
                  if (state.canSubmit || state.status is SignUpStatus.Loading)
                      MaterialTheme.colorScheme.primary
                  else Disabled
              )
              .clickable(enabled = state.canSubmit, role = Role.Button, onClick = onSubmit)
              .semantics { testTag = SignUpTags.Submit }
              .padding(12.dp),
          contentAlignment = Alignment.Center,
      ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (state.status is SignUpStatus.Loading) SignUpSpinner()
          Label(
              submitLabel,
              color =
                  if (state.canSubmit || state.status is SignUpStatus.Loading)
                      MaterialTheme.colorScheme.onPrimary
                  else MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.titleSmall,
          )
        }
      }
      Spacer(Modifier.height(16.dp))
      Row(
          Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Label(
            stringResource(R.string.signup_already_have_account),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Link(
            stringResource(R.string.signup_log_in),
            SignUpTags.LogIn,
            onLogIn,
            enabled = editingEnabled,
        )
      }
      Spacer(Modifier.height(20.dp))
    }
    SnackbarHost(
        snackbarHostState,
        Modifier.align(Alignment.BottomCenter)
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
      Snackbar(
          it,
          shape = RoundedCornerShape(14.dp),
          containerColor = MaterialTheme.colorScheme.onSurface,
          contentColor = MaterialTheme.colorScheme.background,
      )
    }
  }
}

@Composable
private fun FormField(
    field: SignUpField,
    form: SignUpForm,
    isError: Boolean,
    enabled: Boolean,
    onChange: (String) -> Unit,
    onBlur: () -> Unit,
) {
  val label =
      stringResource(
          when (field) {
            SignUpField.FullName -> R.string.signup_full_name
            SignUpField.Email -> R.string.signup_email
            SignUpField.Password -> R.string.signup_password
            SignUpField.ConfirmPassword -> R.string.signup_confirm_password
          }
      )
  val value =
      when (field) {
        SignUpField.FullName -> form.fullName
        SignUpField.Email -> form.email
        SignUpField.Password -> form.password
        SignUpField.ConfirmPassword -> form.confirmPassword
      }
  val passwordField = field == SignUpField.Password || field == SignUpField.ConfirmPassword
  var visible by remember { mutableStateOf(false) }
  var focused by remember { mutableStateOf(false) }
  Label(
      label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Spacer(Modifier.height(6.dp))
  BasicTextField(
      value,
      onChange,
      enabled = enabled,
      singleLine = true,
      textStyle =
          MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
      keyboardOptions =
          KeyboardOptions(
              keyboardType =
                  when {
                    passwordField -> KeyboardType.Password
                    field == SignUpField.Email -> KeyboardType.Email
                    else -> KeyboardType.Text
                  }
          ),
      visualTransformation =
          if (passwordField && !visible) PasswordVisualTransformation()
          else VisualTransformation.None,
      modifier =
          Modifier.fillMaxWidth()
              .heightIn(min = 50.dp)
              .onFocusChanged {
                if (focused && !it.isFocused) onBlur()
                focused = it.isFocused
              }
              .semantics {
                testTag = SignUpTags.input(field)
                contentDescription = label
              },
      decorationBox = { input ->
        Row(
            Modifier.border(
                    if (focused || isError) 2.dp else 1.dp,
                    if (isError) MaterialTheme.colorScheme.error
                    else if (focused) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    MaterialTheme.shapes.extraSmall,
                )
                .padding(start = 14.dp, end = if (passwordField) 2.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
            if (value.isEmpty() && field == SignUpField.FullName) {
              Label(
                  stringResource(R.string.signup_full_name_placeholder),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            input()
          }
          if (passwordField) {
            val action =
                stringResource(
                    if (visible) R.string.signup_hide_password else R.string.signup_show_password
                )
            Box(
                Modifier.size(48.dp)
                    .clip(MaterialTheme.shapes.large)
                    .clickable(enabled = enabled, role = Role.Button) { visible = !visible }
                    .semantics {
                      testTag = SignUpTags.visibility(field)
                      contentDescription = action
                    },
                contentAlignment = Alignment.Center,
            ) {
              Image(
                  painterResource(
                      if (visible) R.drawable.signup_eye_closed else R.drawable.signup_eye
                  ),
                  null,
                  Modifier.size(20.dp),
                  colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
              )
            }
          }
        }
      },
  )
}

@Composable
private fun Rule(text: String, satisfied: Boolean, tag: String) {
  val ruleState =
      stringResource(if (satisfied) R.string.signup_rule_met else R.string.signup_rule_unmet)
  Row(
      Modifier.semantics {
        testTag = tag
        stateDescription = ruleState
      },
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(14.dp)) {
      if (satisfied)
          Image(
              painterResource(R.drawable.signup_check),
              null,
              Modifier.size(14.dp),
              colorFilter = ColorFilter.tint(Success),
          )
    }
    Spacer(Modifier.width(6.dp))
    Label(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (satisfied) Success else MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun ErrorLabel(text: String, tag: String, modifier: Modifier = Modifier) {
  Row(
      modifier.padding(top = 6.dp).semantics(mergeDescendants = true) {
        testTag = tag
        liveRegion = LiveRegionMode.Polite
        error(text)
      },
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Image(
        painterResource(R.drawable.signup_error),
        null,
        Modifier.size(14.dp),
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onErrorContainer),
    )
    Spacer(Modifier.width(6.dp))
    Label(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
    )
  }
}

@Composable
private fun Link(text: String, tag: String, onClick: () -> Unit, enabled: Boolean = true) {
  Label(
      text,
      Modifier.heightIn(min = 48.dp)
          .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
          .semantics { testTag = tag }
          .padding(horizontal = 6.dp, vertical = 14.dp),
      style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
      color = MaterialTheme.colorScheme.onErrorContainer,
      decoration = TextDecoration.Underline,
  )
}

@Composable
private fun Label(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
    decoration: TextDecoration? = null,
) {
  BasicText(text, modifier, style = style.copy(color = color, textDecoration = decoration))
}

private fun FieldError.message(): Int =
    when (this) {
      FieldError.Required -> R.string.signup_required
      FieldError.InvalidDomain -> R.string.signup_invalid_domain
      FieldError.PasswordRules -> R.string.signup_password_rules
      FieldError.PasswordMismatch -> R.string.signup_password_mismatch
    }

private fun SignUpResult.Failure.message(): Int =
    when (this) {
      SignUpResult.InvalidDomain -> R.string.signup_invalid_domain
      SignUpResult.AlreadyInUse -> R.string.signup_duplicate_email
      SignUpResult.NetworkError -> R.string.signup_network_error
      SignUpResult.InvalidPassword -> R.string.signup_password_rules
      SignUpResult.TooManyRequests -> R.string.signup_too_many_requests
      SignUpResult.DisplayNameError -> R.string.signup_display_name_error
      SignUpResult.UnexpectedError -> R.string.signup_unknown_error
    }
