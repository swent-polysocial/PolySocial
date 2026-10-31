// Contributors: Claude (wrote these tests, #231).
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
      MatchGroup(id, capacity, members.toList())

  private fun rankedIds(groups: List<MatchGroup>, student: MatchProfile = me) =
      rankGroups(student, groups).map { it.group.id }

  @Test
  fun noGroups_giveAnEmptyList() {
    assertEquals(emptyList<RankedGroup>(), rankGroups(me, emptyList()))
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
        MatchGroup(
            "over-full",
            capacity = 2,
            members = listOf(member("m5", year = "BA3"), member("m6"), member("m7")),
        )
    val noPlaces = MatchGroup("no-places", capacity = 0, members = emptyList())
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

    assertEquals(emptyList<RankedGroup>(), rankGroups(me, listOf(full, mine)))
  }

  @Test
  fun sharedInterests_rankAGroupHigher_andAreGivenAsReasons() {
    val twoShared = group("g3", member("m1", interests = setOf("hiking", "music", "chess")))
    val oneShared = group("g2", member("m2", interests = setOf("games")))
    val noneShared = group("g1", member("m3", interests = setOf("chess")))
    val noMembers = group("no-members")

    val ranked = rankGroups(me, listOf(noMembers, noneShared, oneShared, twoShared))

    assertEquals(listOf("g3", "g2", "g1", "no-members"), ranked.map { it.group.id })
    assertEquals(listOf(6, 3, 0, 0), ranked.map { it.score })
    assertEquals(
        listOf(setOf("hiking", "music"), setOf("games"), emptySet(), emptySet()),
        ranked.map { it.sharedInterests },
    )
    assertEquals(RankedGroup(noMembers, 0, emptySet(), 0, 0), ranked.last())
  }

  @Test
  fun theSameSection_ranksAGroupHigher() {
    val twoInSection = group("g3", member("m1", section = "IN"), member("m2", section = "IN"))
    val oneInSection = group("g2", member("m3", section = "IN"), member("m4"))
    val noneInSection = group("g1", member("m5"), member("m6"))

    val ranked = rankGroups(me, listOf(noneInSection, oneInSection, twoInSection))

    assertEquals(listOf("g3", "g2", "g1"), ranked.map { it.group.id })
    assertEquals(listOf(4, 2, 0), ranked.map { it.score })
    assertEquals(listOf(2, 1, 0), ranked.map { it.membersInSameSection })
  }

  @Test
  fun theSameYear_ranksAGroupHigher() {
    val twoInYear = group("g3", member("m1", year = "BA3"), member("m2", year = "BA3"))
    val oneInYear = group("g2", member("m3", year = "BA3"), member("m4"))
    val noneInYear = group("g1", member("m5"), member("m6"))

    val ranked = rankGroups(me, listOf(noneInYear, oneInYear, twoInYear))

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

    val ranked = rankGroups(me, listOf(threeMembers)).single()

    // m1: 2 interests × 3 + section 2 + year 1 = 9; m2: 3 + 2 = 5; m3: 1.
    assertEquals(15, ranked.score)
    assertEquals(listOf("hiking", "music"), ranked.sharedInterests.toList())
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

    val ranked = rankGroups(me, groups)

    assertEquals(listOf(2, 2, 2, 2), ranked.map { it.score })
    assertEquals(listOf("a", "ab", "b", "c"), ranked.map { it.group.id })
  }

  @Test
  fun aStudentWithoutInterests_isRankedOnSectionAndYearOnly() {
    val student = me.copy(interests = emptySet())
    val sameInterests = group("g3", member("m1", interests = setOf("music", "hiking", "games")))
    val sameSection = group("g2", member("m2", section = "IN", interests = setOf("music")))
    val sameYear = group("g1", member("m3", year = "BA3"))

    val ranked = rankGroups(student, listOf(sameInterests, sameSection, sameYear))

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

    val ranked = rankGroups(student, listOf(blanks)).single()

    assertEquals(0, ranked.score)
    assertEquals(0, ranked.membersInSameSection)
    assertEquals(0, ranked.membersInSameYear)
    assertEquals(
        0,
        rankGroups(me, listOf(group("g", member("m3", section = "", year = "")))).single().score,
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
    val expected = rankGroups(me, groups)
    // g1 and g2 tie at 6, so g1 comes first by id.
    assertEquals(listOf("g1", "g2", "g3", "g5", "g4"), expected.map { it.group.id })

    val random = Random(231)
    repeat(20) {
      val student = me.copy(interests = me.interests.shuffled(random).toSet())
      val shuffled = groups.shuffled(random).map { it.copy(members = it.members.shuffled(random)) }

      val ranked = rankGroups(student, shuffled)

      assertEquals(expected.map { it.group.id }, ranked.map { it.group.id })
      assertEquals(expected.map { it.score }, ranked.map { it.score })
      assertEquals(
          expected.map { it.sharedInterests.toList() },
          ranked.map { it.sharedInterests.toList() },
      )
      assertEquals(expected.map { it.membersInSameSection }, ranked.map { it.membersInSameSection })
      assertEquals(expected.map { it.membersInSameYear }, ranked.map { it.membersInSameYear })
    }
  }
}
