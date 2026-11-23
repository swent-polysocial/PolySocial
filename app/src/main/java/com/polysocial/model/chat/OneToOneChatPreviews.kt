// Contributors: Claude (Opus 5.5, Anthropic) (each one-to-one chat with its latest message, for the
// Chats tab, #432).
package com.polysocial.model.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/** A one-to-one chat as the Chats tab lists it: the chat and its latest message. */
data class OneToOneChatPreview(val chat: OneToOneChat, val latestMessage: Message)

/** What [observeChatPreviews] emits. */
sealed interface OneToOneChatPreviewsResult {
  /** The chats, or the latest message of one of them, haven't answered yet. */
  data object Loading : OneToOneChatPreviewsResult

  /**
   * The student's one-to-one chats that have a message, in no particular order. A chat whose latest
   * message can't be read is left out.
   *
   * @property fromCache true when the chats or any latest message come from the offline cache.
   */
  data class Success(val previews: List<OneToOneChatPreview>, val fromCache: Boolean) :
      OneToOneChatPreviewsResult

  /** The chats couldn't be read ([MyOneToOneChatsResult]'s two failures). The flow then ends. */
  data object Failed : OneToOneChatPreviewsResult
}

/**
 * The signed-in student's one-to-one chats, each with its latest message
 * ([OneToOneChatRepository.observeLatestMessage], one message per chat), for the Chats tab (#432),
 * as [ChatsRepository] does for groups. Only chats with a message are listed (decided in #365): the
 * app never creates an empty chat, but the first message's chat can be seen before its message.
 *
 * Each chat's latest-message listener starts when the chat is listed and stops when it no longer
 * is; a change of the list that keeps the same chats (only `fromCache`, or a friend's account
 * deleted) restarts none of them. It emits [OneToOneChatPreviewsResult.Loading] until the chats and
 * each of their latest messages have answered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun OneToOneChatRepository.observeChatPreviews(): Flow<OneToOneChatPreviewsResult> = channelFlow {
  val chats = observeMyChats().shareIn(this, SharingStarted.Lazily, replay = 1)
  val latestById =
      chats
          .map { (it as? MyOneToOneChatsResult.Success)?.chats?.map { chat -> chat.id }?.toSet() }
          .distinctUntilChanged()
          .flatMapLatest { ids ->
            if (ids.isNullOrEmpty()) flowOf(emptyMap())
            else combine(ids.map { id -> observeLatestMessage(id).map { id to it } }) { it.toMap() }
          }
  combine(chats, latestById) { result, latest -> previews(result, latest) }
      .distinctUntilChanged()
      .collect { send(it) }
}

private fun previews(
    result: MyOneToOneChatsResult,
    latest: Map<String, LatestMessageResult>,
): OneToOneChatPreviewsResult =
    when (result) {
      MyOneToOneChatsResult.Loading -> OneToOneChatPreviewsResult.Loading
      MyOneToOneChatsResult.NetworkError,
      MyOneToOneChatsResult.UnexpectedError -> OneToOneChatPreviewsResult.Failed
      is MyOneToOneChatsResult.Success -> {
        val answers = result.chats.map { it to latest[it.id] }
        if (answers.any { (_, answer) -> answer == null || answer == LatestMessageResult.Loading })
            OneToOneChatPreviewsResult.Loading
        else
            OneToOneChatPreviewsResult.Success(
                previews =
                    answers.mapNotNull { (chat, answer) ->
                      (answer as? LatestMessageResult.Success)?.message?.let {
                        OneToOneChatPreview(chat, it)
                      }
                    },
                fromCache =
                    result.fromCache ||
                        answers.any { (_, answer) ->
                          (answer as? LatestMessageResult.Success)?.fromCache == true
                        },
            )
      }
    }
