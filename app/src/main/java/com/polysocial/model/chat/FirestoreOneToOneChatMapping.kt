// Contributors: Claude (Opus 5.5, Anthropic) (drafted the Firestore mapping for one-to-one chats,
// #428).
package com.polysocial.model.chat

import com.polysocial.model.friend.uidsOfFriendshipId

/** Top-level collection of the chats between two friends, one per pair (#365). */
const val CHATS_COLLECTION = "chats"

/** The chat's students' UIDs: also the field of the own-chats query. */
const val CHAT_UIDS = "uids"

/**
 * The fields of a new chat between the two students [uids], in the order of the pair ID: exactly
 * `uids`, the only field the Security Rules accept.
 */
fun newChatFirestoreMap(uids: List<String>): Map<String, Any> = mapOf(CHAT_UIDS to uids)

/**
 * Reads the chat `chats/[id]` from its stored [data] as the student [uid] sees it, written by hand
 * like the other documents (no automatic mapping).
 *
 * Returns null when there is no data, when [id] isn't a pair ID with [uid] as one of its halves, or
 * when `uids` isn't a list of UIDs that holds [uid] and only the pair's UIDs, so one malformed
 * document can't crash the Chats tab. The other student is the UID in `uids` that isn't [uid], or
 * null once `deleteAccount` took it out.
 */
fun oneToOneChatFromFirestore(id: String, data: Map<String, Any?>?, uid: String): OneToOneChat? {
  val pair = uidsOfFriendshipId(id) ?: return null
  val stored = data?.get(CHAT_UIDS) as? List<*> ?: return null
  val uids = stored.map { it as? String ?: return null }
  if (uid !in uids || !pair.containsAll(uids)) return null
  return OneToOneChat(id, uids.firstOrNull { it != uid })
}
