// Contributors: Claude (matching heuristic, and member UIDs after review, #231).
package com.polysocial.model.group

/**
 * The fields of a student's public profile that matching uses. They are always visible, because
 * matching needs them. [section] and [year] are codes such as `IN` and `BA3`, and a blank one means
 * the student hasn't set it. [interests] are ids from the fixed list of interests (#230), empty
 * when the student hasn't picked any.
 */
data class MatchProfile(
    val uid: String,
    val section: String,
    val year: String,
    val interests: Set<String>,
)

/**
 * One group of an event, as matching sees it: its unique [id] (the group's document ID), how many
 * students it can hold ([capacity]), its stored [memberUids] and the [members]' profiles the caller
 * could read. The caller maps its group model to this, so matching doesn't depend on how groups are
 * stored. [memberUids] decide how full the group is and whether the student is in it; [members]
 * only score it. A member whose profile is missing (not written yet, a deleted account, a failed
 * read) stays in [memberUids] and is simply absent from [members]: the group still counts as full
 * or as the student's own, and that member adds no points. [memberUids] are distinct: a uid listed
 * twice would count twice.
 */
data class MatchGroup(
    val id: String,
    val capacity: Int,
    val memberUids: List<String>,
    val members: List<MatchProfile>,
)

/**
 * A group [rankGroups] suggests, with its [score] and why it fits, which Find a group shows (#233).
 *
 * @property sharedInterests The student's interests that at least one member also has, each once,
 *   in id order.
 * @property membersInSameSection How many members are in the student's section.
 * @property membersInSameYear How many members are in the student's year.
 */
data class RankedGroup(
    val group: MatchGroup,
    val score: Int,
    val sharedInterests: List<String>,
    val membersInSameSection: Int,
    val membersInSameYear: Int,
)

/** Points a member adds per interest they share with the student. */
private const val INTEREST_POINTS = 3

/** Points a member adds when they are in the student's section. */
private const val SECTION_POINTS = 2

/** Points a member adds when they are in the student's year. */
private const val YEAR_POINTS = 1

/**
 * The [groups] of one event that [student] can join, best fit first (#231). This is a deterministic
 * heuristic, not an optimal assignment: each group is scored on its own.
 *
 * **Score.** Each member of a group adds:
 * - 3 points per interest they share with the student,
 * - 2 points if they are in the student's section,
 * - 1 point if they are in the student's year.
 *
 * A group's score is the sum over its members. A blank section or year never matches, so two
 * students who haven't set one don't count as alike. A student without interests is ranked on
 * section and year only.
 *
 * **Order.** Highest score first. Groups with the same score are in ascending [MatchGroup.id] order
 * (plain string order, the same in every locale), so the same groups always give the same list,
 * whatever order they come in.
 *
 * **Left out.** Full groups (as many [MatchGroup.memberUids] as their [MatchGroup.capacity], or
 * more) and any group whose [MatchGroup.memberUids] contain [student]'s uid, whether or not their
 * profiles were read. A group with nothing in common is still suggested, last. With no groups, or
 * none left, the list is empty.
 *
 * It has no Android or Firebase dependency, so it runs on the device. If matching moves
 * server-side, only its caller changes.
 */
fun rankGroups(student: MatchProfile, groups: List<MatchGroup>): List<RankedGroup> =
    groups
        .filter { group ->
          group.memberUids.size < group.capacity && student.uid !in group.memberUids
        }
        .map { group -> rank(student, group) }
        .sortedWith(compareByDescending<RankedGroup> { it.score }.thenBy { it.group.id })

private fun rank(student: MatchProfile, group: MatchGroup): RankedGroup {
  val sharedByMember = group.members.map { student.interests intersect it.interests }
  val inSameSection = group.members.count { sameCode(student.section, it.section) }
  val inSameYear = group.members.count { sameCode(student.year, it.year) }
  return RankedGroup(
      group = group,
      score =
          sharedByMember.sumOf { it.size } * INTEREST_POINTS +
              inSameSection * SECTION_POINTS +
              inSameYear * YEAR_POINTS,
      sharedInterests = sharedByMember.flatten().distinct().sorted(),
      membersInSameSection = inSameSection,
      membersInSameYear = inSameYear,
  )
}

/** Whether two section (or year) codes match. A blank code never matches, not even another one. */
private fun sameCode(studentCode: String, memberCode: String): Boolean =
    studentCode.isNotBlank() && studentCode == memberCode
