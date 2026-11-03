// Contributors: Claude (fixed section and year lists for the profile step, #34; the interest list,
// #230).
package com.polysocial.model.user

/**
 * EPFL study sections a student can pick, by their official codes (EPFL academic organisation), in
 * alphabetical order. SHS is left out: it is a humanities programme, not a study section.
 */
val SECTIONS: List<String> =
    listOf(
        "AR",
        "CGC",
        "CMS",
        "EL",
        "GC",
        "GM",
        "IF",
        "IN",
        "MA",
        "MT",
        "MTE",
        "MX",
        "NX",
        "PH",
        "SC",
        "SIE",
        "SIQ",
        "SV",
    )

/** Study years a student can pick: Bachelor 1 to 6, then Master 1 to 4. */
val YEARS: List<String> = (1..6).map { "BA$it" } + (1..4).map { "MA$it" }

/**
 * Interests a student can pick on the profile step (#230), by the codes stored in `users/{uid}` and
 * `publicProfiles/{uid}`. The app shows each code's label from `strings.xml`, so a label can change
 * without touching stored data. This is also the order the chips are shown and the codes stored in.
 * The Security Rules repeat this list (`interestCodes` in `firestore.rules`), and a rules test
 * fails if the two differ.
 */
val INTERESTS: List<String> =
    listOf(
        "sports",
        "outdoors",
        "music",
        "parties",
        "cinema",
        "games",
        "photography",
        "cooking",
        "tech",
        "startups",
        "arts",
        "volunteering",
    )

/**
 * [interests] as stored: the codes of [INTERESTS] among them, in that list's order. The private and
 * public profiles both store this list, and the Security Rules compare the two with `==`, so the
 * order must be the same. Unknown codes are left out, since the rules refuse them.
 */
fun orderedInterests(interests: Collection<String>): List<String> = INTERESTS.filter {
  it in interests
}
