// Contributors: Claude (Opus 5.5, Anthropic) (Unsend's state, confirmation and failure notice, from
// #430's Figma section 25, #436).
package com.polysocial.ui.chat

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.polysocial.R
import com.polysocial.model.chat.UnsendMessageResult
import com.polysocial.ui.common.CenteredConfirmationSheet
import com.polysocial.ui.profile.privacyTextStyle
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Ink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Tags of Unsend's parts, shared by every chat. */
internal object ChatUnsendTags {
  const val MENU = "chat_unsend_menu"
  const val CONFIRM = "chat_unsend_confirm"
  const val CANCEL = "chat_unsend_cancel"
  const val NOTICE = "chat_unsend_notice"
  const val TRY_AGAIN = "chat_unsend_try_again"

  /** The bubble of the message [id], which a long press opens Unsend's menu on. */
  fun bubble(id: String) = "chat_bubble_$id"
}

/** The message being unsent, [messageId], and where its unsend stands. */
internal data class ChatUnsend(val messageId: String, val stage: Stage) {
  enum class Stage {
    /** The confirmation sheet is open. */
    CONFIRMING,
    UNSENDING,

    /** Refused offline: "You're offline. Nothing changed.", with Try again. */
    OFFLINE,

    /** Any other failure: "Couldn't unsend the message", with Try again. */
    FAILED,
  }
}

/**
 * A chat's Unsend (#436), for the one-to-one chat and later the group chat (#440): the message the
 * student asked to unsend, its confirmation, then the call and its failure, kept to try again.
 * Nothing is shown once it succeeds: the message listener brings the placeholder.
 */
internal class ChatUnsends(private val scope: CoroutineScope) {
  private val current = MutableStateFlow<ChatUnsend?>(null)

  val unsend: StateFlow<ChatUnsend?> = current.asStateFlow()

  /**
   * Asks to confirm unsending [messageId], replacing a failure's notice. Ignored while unsending.
   */
  fun ask(messageId: String) {
    current.update {
      if (it?.stage == ChatUnsend.Stage.UNSENDING) it
      else ChatUnsend(messageId, ChatUnsend.Stage.CONFIRMING)
    }
  }

  /**
   * Closes the confirmation or the failure's notice, keeping the message. Ignored while unsending.
   */
  fun close() {
    current.update { if (it?.stage == ChatUnsend.Stage.UNSENDING) it else null }
  }

  /**
   * Unsends the confirmed or failed message with [deliver]: Unsent clears it, offline or any other
   * failure keeps it, to try again. A cancelled call puts it back as it was. Ignored with nothing
   * to unsend or while unsending.
   */
  fun confirm(deliver: suspend (messageId: String) -> UnsendMessageResult) {
    val asked = current.value ?: return
    if (asked.stage == ChatUnsend.Stage.UNSENDING) return
    val pending = asked.copy(stage = ChatUnsend.Stage.UNSENDING)
    if (!current.compareAndSet(asked, pending)) return
    scope.launch {
      val result =
          try {
            deliver(asked.messageId)
          } catch (cancelled: CancellationException) {
            current.compareAndSet(pending, asked)
            throw cancelled
          } catch (_: Exception) {
            UnsendMessageResult.UnexpectedError
          }
      val next =
          when (result) {
            UnsendMessageResult.Unsent -> null
            UnsendMessageResult.NetworkError -> pending.copy(stage = ChatUnsend.Stage.OFFLINE)
            UnsendMessageResult.UnexpectedError -> pending.copy(stage = ChatUnsend.Stage.FAILED)
          }
      current.compareAndSet(pending, next)
    }
  }
}

/**
 * Unsend's confirmation sheet (Figma "Unsend · confirm"), with [body] saying who won't see the
 * message any more.
 */
@Composable
internal fun UnsendSheet(body: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
  CenteredConfirmationSheet(
      icon = R.drawable.ic_chat_unsend,
      title = stringResource(R.string.chat_unsend_title),
      body = body,
      confirm = stringResource(R.string.chat_unsend),
      keep = R.string.chat_unsend_cancel,
      confirmTag = ChatUnsendTags.CONFIRM,
      keepTag = ChatUnsendTags.CANCEL,
      onConfirm = onConfirm,
      onKeep = onCancel,
  )
}

/**
 * Unsend's failure (Figma "Unsend · offline" and "Unsend · failed"), as the Friends screen's
 * notice: a snackbar with Try again, which accessibility services can also dismiss.
 */
@Composable
internal fun UnsendNotice(
    stage: ChatUnsend.Stage,
    onTryAgain: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Snackbar(
      modifier =
          modifier.testTag(ChatUnsendTags.NOTICE).semantics {
            liveRegion = LiveRegionMode.Polite
            dismiss {
              onDismiss()
              true
            }
          },
      shape = RoundedCornerShape(12.dp),
      containerColor = Ink,
      contentColor = Bg,
      action = {
        TextButton(onClick = onTryAgain, modifier = Modifier.testTag(ChatUnsendTags.TRY_AGAIN)) {
          Text(
              stringResource(R.string.chat_unsend_try_again),
              style = privacyTextStyle(13, 17f, FontWeight.Bold),
              color = Bg,
          )
        }
      },
  ) {
    Text(
        stringResource(
            if (stage == ChatUnsend.Stage.OFFLINE) R.string.chat_unsend_offline
            else R.string.chat_unsend_failed
        ),
        style = privacyTextStyle(13, 17f),
    )
  }
}
