// Contributors: Claude Opus 5.5 (wrote seedRequest in the friend repository's emulator tests,
// #357); Konstantinos Perdikeas (directed, reviewed and owns them).
package com.polysocial.emulator

import com.polysocial.model.friend.FRIENDSHIPS_COLLECTION
import com.polysocial.model.friend.friendshipId
import java.time.Instant

/**
 * Stores a friend request [from] sent to [to], as the app writes it, with the rules off; with
 * [accepted], the two are friends.
 */
suspend fun FirebaseEmulator.seedFriendship(from: String, to: String, accepted: Boolean = false) =
    createAsAdmin(
        FRIENDSHIPS_COLLECTION,
        checkNotNull(friendshipId(from, to)) { "$from and $to can't be a pair" },
        buildMap {
          put("uids", listOf(from, to).sorted())
          put("requestedBy", from)
          put("status", if (accepted) "accepted" else "pending")
          put("createdAt", Instant.parse("2030-02-01T12:00:00Z"))
          if (accepted) put("acceptedAt", Instant.parse("2030-02-01T13:00:00Z"))
        },
    )
