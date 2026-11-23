// Contributors: OpenAI Codex (the draft, its stages and its retry under one ID, #238); Claude
// (Opus 5.5, Anthropic) (moved here from GroupChatViewModel to share them with the one-to-one
// chat, #431).
package com.polysocial.ui.chat

import com.polysocial.model.chat.MAX_MESSAGE_LENGTH
import com.polysocial.model.chat.SendMessageResult
import com.polysocial.model.chat.validateMessageText
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** The composer's text, the ID it is sent under, and where its sending stands. */
internal data class ChatDraft(
    val text: String = "",
    val id: String? = null,
    val stage: Stage = Stage.EDITING,
) {
  enum class Stage {
    EDITING,
    SENDING,
    OFFLINE,
    FAILED,
  }

  val canSend
    get() = stage != Stage.SENDING && validateMessageText(text) == null
}

/**
 * A chat's draft, shared by the group chat (#238) and the one-to-one chat (#431). An unchanged
 * failed draft keeps its message ID across manual retries, so a send whose answer was lost can't be
 * stored twice; editing it to another message gives it a new ID.
 */
internal class ChatDrafts(private val scope: CoroutineScope) {
  private val current = MutableStateFlow(ChatDraft())

  val draft: StateFlow<ChatDraft> = current.asStateFlow()

  /**
   * Replaces the text, cut to [MAX_MESSAGE_LENGTH] without splitting an emoji. Ignored while
   * sending.
   */
  fun edit(text: String) {
    val draft = current.value
    if (draft.stage == ChatDraft.Stage.SENDING) return
    val prefix = text.take(MAX_MESSAGE_LENGTH)
    val limited =
        if (text.length > prefix.length && prefix.last().isHighSurrogate()) prefix.dropLast(1)
        else prefix
    val sameMessage = draft.text.trim() == limited.trim()
    current.value = draft.copy(text = limited, id = draft.id.takeIf { sameMessage })
  }

  /** Drops the draft, for example once the chat can't be written to any more. */
  fun clear() {
    current.value = ChatDraft()
  }

  /**
   * Sends the draft with [deliver] under its ID (a new one for a new message), unless it can't be
   * sent. Sent clears it; offline or any other failure keeps it, to retry. A cancelled send puts it
   * back as it was.
   */
  fun send(deliver: suspend (text: String, id: String) -> SendMessageResult) {
    val draft = current.value
    if (!draft.canSend) return
    val id = draft.id ?: UUID.randomUUID().toString()
    val pending = draft.copy(id = id, stage = ChatDraft.Stage.SENDING)
    if (!current.compareAndSet(draft, pending)) return
    scope.launch {
      val result =
          try {
            deliver(pending.text, id)
          } catch (cancelled: CancellationException) {
            current.compareAndSet(pending, draft)
            throw cancelled
          } catch (_: Exception) {
            SendMessageResult.UnexpectedError
          }
      val next =
          when (result) {
            SendMessageResult.Sent -> ChatDraft()
            SendMessageResult.NetworkError -> pending.copy(stage = ChatDraft.Stage.OFFLINE)
            else -> pending.copy(stage = ChatDraft.Stage.FAILED)
          }
      current.compareAndSet(pending, next)
    }
  }
}

/**
 * Today's date in [clock]'s zone, emitted again at each midnight, for the timeline's day labels.
 */
internal fun chatToday(clock: Clock): Flow<LocalDate> = flow {
  val zone = clock.zone
  while (true) {
    val now = clock.instant()
    val date = now.atZone(zone).toLocalDate()
    emit(date)
    val midnight = date.plusDays(1).atStartOfDay(zone).toInstant()
    delay(Duration.between(now, midnight).toMillis().coerceAtLeast(1))
  }
}
