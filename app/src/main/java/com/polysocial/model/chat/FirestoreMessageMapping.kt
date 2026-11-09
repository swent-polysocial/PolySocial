// Contributors: Claude (Opus 5.5, Anthropic) (drafted the Firestore mapping for messages, #237).
package com.polysocial.model.chat

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue

/** Subcollection of `events/{eventId}/groups/{groupId}` holding the group's chat. */
const val MESSAGES_COLLECTION = "messages"

/** The server's time in a message document, which orders the chat. */
const val MESSAGE_CREATED_AT = "createdAt"

/**
 * The fields of a message [senderId] sends with [text]: exactly the sender, the text and the
 * server's time, the only fields the Security Rules accept.
 */
fun newMessageFirestoreMap(senderId: String, text: String): Map<String, Any> =
    mapOf(
        "senderId" to senderId,
        "text" to text,
        MESSAGE_CREATED_AT to FieldValue.serverTimestamp(),
    )

/**
 * Reads the message [id] from its stored [data], written by hand like events and groups (no
 * automatic mapping).
 *
 * Returns null when there is no data or when `senderId`, `text` or `createdAt` is missing or has
 * the wrong type, so one malformed document can't crash the chat.
 */
fun messageFromFirestore(id: String, data: Map<String, Any?>?): Message? {
  if (data == null) return null
  val senderId = data["senderId"] as? String ?: return null
  val text = data["text"] as? String ?: return null
  val createdAt = data[MESSAGE_CREATED_AT] as? Timestamp ?: return null
  return Message(id = id, senderId = senderId, text = text, createdAt = createdAt.toInstant())
}
