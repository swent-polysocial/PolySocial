// Contributors: Claude (fixed section and year lists for the profile step, #34).
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
