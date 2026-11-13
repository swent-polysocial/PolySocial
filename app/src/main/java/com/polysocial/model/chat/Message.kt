// Contributors: Claude (Opus 5.5, Anthropic) (drafted the chat message model, #237).
// Contributors: OpenAI Codex (explicit deleted message author, #241).
package com.polysocial.model.chat

import java.time.Instant

/** Empty senderId written only by account cleanup; it links to no account. */
const val DELETED_MESSAGE_SENDER = ""

/**
 * The most characters a message's text may have, as the Security Rules check. Counted in UTF-16
 * units, like Kotlin's `String.length` and the rules' `size()`, so an emoji counts as 2.
 */
const val MAX_MESSAGE_LENGTH = 1000

/**
 * A message in a group's chat, stored as `events/{eventId}/groups/{groupId}/messages/{id}`. A
 * one-to-one chat is a two-member group, so it uses the same model.
 *
 * @property senderId the sender UID, or [DELETED_MESSAGE_SENDER] after account deletion.
 * @property text what they wrote, 1 to [MAX_MESSAGE_LENGTH] characters.
 * @property createdAt when the server stored it, which orders the chat.
 */
data class Message(
    val id: String,
    val senderId: String,
    val text: String,
    val createdAt: Instant,
)

/** Why a text can't be sent as a message. */
enum class MessageTextError {
  /** Nothing but whitespace (spaces, tabs, line breaks), or nothing at all. */
  BLANK,

  /** Longer than [MAX_MESSAGE_LENGTH] once trimmed. */
  TOO_LONG,
}

/**
 * Checks [text] as [MessageRepository.sendMessage] does, so a screen can disable Send or explain
 * the limit before sending. The text is trimmed first (leading and trailing whitespace, line breaks
 * included), since that is what gets sent; returns null when the trimmed text can be sent.
 */
fun validateMessageText(text: String): MessageTextError? {
  val trimmed = text.trim()
  return when {
    trimmed.isEmpty() -> MessageTextError.BLANK
    trimmed.length > MAX_MESSAGE_LENGTH -> MessageTextError.TOO_LONG
    else -> null
  }
}
