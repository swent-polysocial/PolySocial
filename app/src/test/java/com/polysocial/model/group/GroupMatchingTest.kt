// Contributors: Claude (wrote these tests, and the missing-profile cases after review, #231);
// Claude (Opus 5.5, Anthropic) (the friends cases, #497).
package com.polysocial.model.group

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMatchingTest {

  private val me =
      MatchProfile(
          uid = "me",
          section = "IN",
          year = "BA3",
          interests = setOf("music", "hiking", "games"),
      )

  /** A member who shares nothing with [me] unless told otherwise. */
  private fun member(
      uid: String,
      section: String = "AR",
      year: String = "MA2",
      interests: Set<String> = emptySet(),
  ) = MatchProfile(uid, section, year, interests)

  private fun group(id: String, vararg members: MatchProfile, capacity: Int = 4) =
      MatchGroup(id, capacity, members.map { it.uid }, members.toList())

  private fun rankedIds(
      groups: List<MatchGroup>,
      student: MatchProfile = me,
      friends: Set<String> = NO_FRIENDS,
  ) = rankGroups(student, groups, friends).map { it.group.id }

  @Test
  fun noGroups_giveAnEmptyList() {
    assertEquals(emptyList<RankedGroup>(), rankGroups(me, emptyList(), NO_FRIENDS))
  }

  @Test
  fun aFullGroup_isLeftOut() {
    val full =
        group(
            "full",
            member("m1", section = "IN", interests = setOf("music")),
            member("m2"),
            member("m3"),
            member("m4"),
        )
    val overFull =
        group("over-full", member("m5", year = "BA3"), member("m6"), member("m7"), capacity = 2)
    val noPlaces = group("no-places", capacity = 0)
    val onePlaceLeft = group("one-place-left", member("m8"), member("m9"), member("m10"))

    assertEquals(
        listOf("one-place-left"),
        rankedIds(listOf(full, overFull, noPlaces, onePlaceLeft)),
    )
  }

  @Test
  fun theStudentsOwnGroup_isLeftOut() {
    val mine = group("mine", me, member("m1", section = "IN", interests = setOf("music")))
    val other = group("other", member("m2"))

    assertEquals(listOf("other"), rankedIds(listOf(mine, other)))
  }

  @Test
  fun onlyFullGroupsAndTheStudentsOwn_giveAnEmptyList() {
    val full = group("full", member("m1"), member("m2"), capacity = 2)
    val mine = group("mine", me)

    assertEquals(emptyList<RankedGroup>(), rankGroups(me, listOf(full, mine), NO_FRIENDS))
  }

  @Test
  fun aMemberWithoutAProfile_stillCounts_forFullAndOwnGroups() {
    val m1 = member("m1", section = "IN", interests = setOf("music"))
    // Four stored members, one profile missing: full, whatever the profiles say.
    val fullByUids =
        MatchGroup(
            "full",
            4,
            listOf("m1", "m2", "m3", "m4"),
            listOf(m1, member("m2"), member("m3")),
        )
    // The student is a stored member whose profile wasn't read: their own group.
    val mine = MatchGroup("mine", 4, listOf("me", "m5"), listOf(member("m5")))
    // Two stored members, one profile missing: open, scored on the one profile read.
    val oneProfile = MatchGroup("open", 4, listOf("m1", "m6"), listOf(m1))

    val ranked = rankGroups(me, listOf(fullByUids, mine, oneProfile), NO_FRIENDS)

    assertEquals(listOf("open"), ranked.map { it.group.id })
    assertEquals(5, ranked.single().score)
    assertEquals(listOf("music"), ranked.single().sharedInterests)
  }

  @Test
  fun sharedInterests_rankAGroupHigher_andAreGivenAsReasons() {
    val twoShared = group("g3", member("m1", interests = setOf("hiking", "music", "chess")))
    val oneShared = group("g2", member("m2", interests = setOf("games")))
    val noneShared = group("g1", member("m3", interests = setOf("chess")))
    val noMembers = group("no-members")

    val ranked = rankGroups(me, listOf(noMembers, noneShared, oneShared, twoShared), NO_FRIENDS)

    assertEquals(listOf("g3", "g2", "g1", "no-members"), ranked.map { it.group.id })
    assertEquals(listOf(6, 3, 0, 0), ranked.map { it.score })
    assertEquals(
        listOf(listOf("hiking", "music"), listOf("games"), emptyList(), emptyList()),
        ranked.map { it.sharedInterests },
    )
    assertEquals(RankedGroup(noMembers, 0, emptyList(), 0, 0, emptyList()), ranked.last())
  }

  @Test
  fun theSameSection_ranksAGroupHigher() {
    val twoInSection = group("g3", member("m1", section = "IN"), member("m2", section = "IN"))
    val oneInSection = group("g2", member("m3", section = "IN"), member("m4"))
    val noneInSection = group("g1", member("m5"), member("m6"))

    val ranked = rankGroups(me, listOf(noneInSection, oneInSection, twoInSection), NO_FRIENDS)

    assertEquals(listOf("g3", "g2", "g1"), ranked.map { it.group.id })
    assertEquals(listOf(4, 2, 0), ranked.map { it.score })
    assertEquals(listOf(2, 1, 0), ranked.map { it.membersInSameSection })
  }

  @Test
  fun theSameYear_ranksAGroupHigher() {
    val twoInYear = group("g3", member("m1", year = "BA3"), member("m2", year = "BA3"))
    val oneInYear = group("g2", member("m3", year = "BA3"), member("m4"))
    val noneInYear = group("g1", member("m5"), member("m6"))

    val ranked = rankGroups(me, listOf(noneInYear, oneInYear, twoInYear), NO_FRIENDS)

    assertEquals(listOf("g3", "g2", "g1"), ranked.map { it.group.id })
    assertEquals(listOf(2, 1, 0), ranked.map { it.score })
    assertEquals(listOf(2, 1, 0), ranked.map { it.membersInSameYear })
  }

  @Test
  fun anInterest_outweighsTheSection_whichOutweighsTheYear() {
    val sharedInterest = group("g3", member("m1", interests = setOf("music")))
    val sameSection = group("g2", member("m2", section = "IN"))
    val sameYear = group("g1", member("m3", year = "BA3"))

    assertEquals(listOf("g3", "g2", "g1"), rankedIds(listOf(sameYear, sameSection, sharedInterest)))
  }

  @Test
  fun theScore_addsUpWhatEachMemberShares() {
    val threeMembers =
        group(
            "g",
            member(
                "m1",
                section = "IN",
                year = "BA3",
                interests = setOf("music", "hiking", "chess"),
            ),
            member("m2", section = "IN", interests = setOf("music")),
            member("m3", year = "BA3"),
        )

    val ranked = rankGroups(me, listOf(threeMembers), NO_FRIENDS).single()

    // m1: 2 interests × 3 + section 2 + year 1 = 9; m2: 3 + 2 = 5; m3: 1.
    assertEquals(15, ranked.score)
    assertEquals(listOf("hiking", "music"), ranked.sharedInterests)
    assertEquals(2, ranked.membersInSameSection)
    assertEquals(2, ranked.membersInSameYear)
  }

  @Test
  fun equalScores_areOrderedByGroupId() {
    val groups =
        listOf(
            group("c", member("m1", section = "IN")),
            group("ab", member("m2", section = "IN")),
            group("a", member("m3", section = "IN")),
            group("b", member("m4", year = "BA3"), member("m5", year = "BA3")),
        )

    val ranked = rankGroups(me, groups, NO_FRIENDS)

    assertEquals(listOf(2, 2, 2, 2), ranked.map { it.score })
    assertEquals(listOf("a", "ab", "b", "c"), ranked.map { it.group.id })
  }

  @Test
  fun aStudentWithoutInterests_isRankedOnSectionAndYearOnly() {
    val student = me.copy(interests = emptySet())
    val sameInterests = group("g3", member("m1", interests = setOf("music", "hiking", "games")))
    val sameSection = group("g2", member("m2", section = "IN", interests = setOf("music")))
    val sameYear = group("g1", member("m3", year = "BA3"))

    val ranked = rankGroups(student, listOf(sameInterests, sameSection, sameYear), NO_FRIENDS)

    assertEquals(listOf("g2", "g1", "g3"), ranked.map { it.group.id })
    assertEquals(listOf(2, 1, 0), ranked.map { it.score })
    assertTrue(ranked.all { it.sharedInterests.isEmpty() })
  }

  @Test
  fun aBlankSectionOrYear_neverMatches() {
    val student = me.copy(section = "", year = " ")
    val blanks =
        group(
            "g",
            member("m1", section = "", year = " "),
            member("m2", section = "IN", year = "BA3"),
        )

    val ranked = rankGroups(student, listOf(blanks), NO_FRIENDS).single()

    assertEquals(0, ranked.score)
    assertEquals(0, ranked.membersInSameSection)
    assertEquals(0, ranked.membersInSameYear)
    assertEquals(
        0,
        rankGroups(me, listOf(group("g", member("m3", section = "", year = ""))), NO_FRIENDS)
            .single()
            .score,
    )
  }

  @Test
  fun theSameGroupsInAnyOrder_giveTheSameRanking() {
    val groups =
        listOf(
            group(
                "g1",
                member("m1", section = "IN", interests = setOf("music")),
                member("m2", year = "BA3"),
            ),
            group("g2", member("m3", interests = setOf("hiking", "games"))),
            group("g3", member("m4", section = "IN"), member("m5", section = "IN", year = "BA3")),
            group("g4", member("m6")),
            group("g5", member("m7", interests = setOf("games"))),
        )
    // g1 and g2 tie at 6, so g1 comes first by id.
    assertRankedTheSameInAnyOrder(groups, NO_FRIENDS, listOf("g1", "g2", "g3", "g5", "g4"))
  }

  /**
   * Ranks [groups] with [friends], checks the order is [expectedIds], then checks that shuffling
   * the groups, their members and the student's interests and friends changes nothing.
   */
  private fun assertRankedTheSameInAnyOrder(
      groups: List<MatchGroup>,
      friends: Set<String>,
      expectedIds: List<String>,
  ) {
    val expected = rankGroups(me, groups, friends)
    assertEquals(expectedIds, expected.map { it.group.id })

    val random = Random(231)
    repeat(20) {
      val student = me.copy(interests = me.interests.shuffled(random).toSet())
      val shuffled =
          groups.shuffled(random).map {
            it.copy(
                memberUids = it.memberUids.shuffled(random),
                members = it.members.shuffled(random),
            )
          }

      val ranked = rankGroups(student, shuffled, friends.shuffled(random).toSet())

      assertEquals(expected.map { it.group.id }, ranked.map { it.group.id })
      assertEquals(expected.map { it.score }, ranked.map { it.score })
      assertEquals(expected.map { it.sharedInterests }, ranked.map { it.sharedInterests })
      assertEquals(expected.map { it.membersInSameSection }, ranked.map { it.membersInSameSection })
      assertEquals(expected.map { it.membersInSameYear }, ranked.map { it.membersInSameYear })
      assertEquals(expected.map { it.friends.toSet() }, ranked.map { it.friends.toSet() })
    }
  }

  @Test
  fun noFriends_orFriendsInNoGroup_addNothing() {
    val groups =
        listOf(
            group("g2", member("m1", section = "IN")),
            group("g1", member("m2", year = "BA3")),
        )

    for (friends in listOf(NO_FRIENDS, setOf("someone-else"))) {
      val ranked = rankGroups(me, groups, friends)

      assertEquals(listOf("g2", "g1"), ranked.map { it.group.id })
      assertEquals(listOf(2, 1), ranked.map { it.score })
      assertTrue(ranked.all { it.friends.isEmpty() })
    }
  }

  @Test
  fun oneFriend_adds10Points_andIsGivenAsAReason() {
    val withFriend = group("g1", member("friend"))
    val withoutFriend = group("g2", member("m1"))

    val ranked = rankGroups(me, listOf(withoutFriend, withFriend), setOf("friend"))

    assertEquals(listOf("g1", "g2"), ranked.map { it.group.id })
    assertEquals(listOf(10, 0), ranked.map { it.score })
    assertEquals(listOf(listOf("friend"), emptyList()), ranked.map { it.friends })
  }

  @Test
  fun twoFriends_add10PointsEach_inMemberOrder() {
    val twoFriends = group("g1", member("friend-b"), member("m1"), member("friend-a"))
    val oneFriend = group("g2", member("friend-c"), member("m2"))

    val ranked =
        rankGroups(me, listOf(oneFriend, twoFriends), setOf("friend-a", "friend-b", "friend-c"))

    assertEquals(listOf("g1", "g2"), ranked.map { it.group.id })
    assertEquals(listOf(20, 10), ranked.map { it.score })
    assertEquals(listOf("friend-b", "friend-a"), ranked.first().friends)
  }

  @Test
  fun aFriend_addsToWhatTheyShare() {
    val friend = member("friend", section = "IN", interests = setOf("music"))

    val ranked = rankGroups(me, listOf(group("g", friend, member("m1"))), setOf("friend")).single()

    // 10 as a friend, 3 for music, 2 for the section.
    assertEquals(15, ranked.score)
    assertEquals(listOf("music"), ranked.sharedInterests)
    assertEquals(1, ranked.membersInSameSection)
    assertEquals(listOf("friend"), ranked.friends)
  }

  @Test
  fun aFriendWithoutAReadableProfile_stillCounts() {
    // The friend is a stored member, but their profile couldn't be read.
    val friendUnread = MatchGroup("g1", 4, listOf("friend", "m1"), listOf(member("m1")))
    val sameSection = group("g2", member("m2", section = "IN", year = "BA3"))

    val ranked = rankGroups(me, listOf(sameSection, friendUnread), setOf("friend"))

    assertEquals(listOf("g1", "g2"), ranked.map { it.group.id })
    assertEquals(listOf(10, 3), ranked.map { it.score })
    assertEquals(listOf("friend"), ranked.first().friends)
  }

  @Test
  fun aFriendInAFullGroupOrTheStudentsOwn_isStillLeftOut() {
    val full = group("full", member("friend-a"), member("m1"), capacity = 2)
    val mine = group("mine", me, member("friend-b"))
    val open = group("open", member("m2"))

    val ranked = rankGroups(me, listOf(full, mine, open), setOf("friend-a", "friend-b"))

    assertEquals(listOf("open"), ranked.map { it.group.id })
    assertEquals(emptyList<String>(), ranked.single().friends)
  }

  @Test
  fun aFriend_outranksAMemberWithTwoSharedInterestsTheSameSectionAndYear() {
    val alike =
        group("g1", member("m1", section = "IN", year = "BA3", interests = setOf("music", "games")))
    val friend = group("g2", member("friend"))

    val ranked = rankGroups(me, listOf(alike, friend), setOf("friend"))

    assertEquals(listOf("g2", "g1"), ranked.map { it.group.id })
    assertEquals(listOf(10, 9), ranked.map { it.score })
  }

  @Test
  fun aGroupSharingMoreInterests_canStillOutrankAFriend() {
    val alike =
        group(
            "g1",
            member(
                "m1",
                section = "IN",
                year = "BA3",
                interests = setOf("music", "games", "hiking"),
            ),
        )
    val friend = group("g2", member("friend"))

    val ranked = rankGroups(me, listOf(friend, alike), setOf("friend"))

    assertEquals(listOf("g1", "g2"), ranked.map { it.group.id })
    assertEquals(listOf(12, 10), ranked.map { it.score })
  }

  @Test
  fun withFriends_theSameGroupsInAnyOrder_giveTheSameRanking() {
    val groups =
        listOf(
            group("g1", member("friend-a"), member("m1", section = "IN")),
            group("g2", member("m2", interests = setOf("music", "games")), member("m3")),
            group("g3", member("friend-b")),
            group("g4", member("m4", section = "IN", year = "BA3", interests = setOf("music"))),
            group("g5", member("friend-c"), member("friend-d")),
        )

    // g5: two friends, 20; g1: a friend and the section, 12; g3: a friend, 10; g2 (two interests)
    // and g4 (an interest, the section and the year) tie at 6, so g2 comes first by id.
    assertRankedTheSameInAnyOrder(
        groups,
        setOf("friend-a", "friend-b", "friend-c", "friend-d"),
        listOf("g5", "g1", "g3", "g2", "g4"),
    )
  }

  private companion object {
    val NO_FRIENDS = emptySet<String>()
  }
}
