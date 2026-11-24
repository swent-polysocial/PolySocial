// Contributors: OpenAI Codex (the history states and the first read's timeout, #238); Claude (Opus
// 5.5, Anthropic) (moved here from GroupChatViewModel to share them with the one-to-one chat, from
// #520's review, #431).
package com.polysocial.ui.chat

import com.polysocial.model.chat.Message
import com.polysocial.model.chat.MessagesResult
import com.polysocial.ui.group.GroupMember
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * A chat's history, as a chat screen shows it: the group chat (#238) or a one-to-one chat (#431).
 */
internal sealed interface ChatHistory {
  data object Loading : ChatHistory

  data object Failed : ChatHistory

  /** The student left the group: only the group chat has it. */
  data object Left : ChatHistory

  data class Ready(
      val messages: List<Message>,
      val members: List<GroupMember>,
  ) : ChatHistory
}

/** How long a chat's first read may take before it fails, with Retry. */
internal const val CHAT_READ_TIMEOUT_MILLIS = 10_000L

/** The messages' result as a history, without its members (the group chat adds them). */
internal fun MessagesResult.toChatHistory(): ChatHistory =
    when (this) {
      MessagesResult.Loading -> ChatHistory.Loading
      MessagesResult.UnexpectedError,
      MessagesResult.NetworkError -> ChatHistory.Failed
      is MessagesResult.Success -> ChatHistory.Ready(messages, emptyList())
    }

/**
 * This history as a chat screen shows it: [ChatHistory.Loading] first, then [ChatHistory.Failed] if
 * only Loading has come after [CHAT_READ_TIMEOUT_MILLIS], so the screen never stays loading. Until
 * the first other result, Loading is held back; from then on every result is shown, a late answer
 * replacing the timeout's Failed.
 */
internal fun Flow<ChatHistory>.failingIfSilent(): Flow<ChatHistory> = channelFlow {
  send(ChatHistory.Loading)
  val timeout = launch {
    delay(CHAT_READ_TIMEOUT_MILLIS)
    send(ChatHistory.Failed)
  }
  var answered = false
  collect { history ->
    if (history != ChatHistory.Loading) {
      answered = true
      timeout.cancel()
    }
    // While the first read is pending, keep the initial Loading or timeout error visible.
    if (answered) send(history)
  }
}
