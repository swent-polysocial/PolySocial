// Contributors: Claude (the chip surface and interest labels shared from the profile step's
// interests screen, after review, #230).
package com.polysocial.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.polysocial.R
import com.polysocial.model.user.INTERESTS
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Border
import com.polysocial.ui.theme.Ink

/**
 * The pill of a Figma choice chip: filled with Ink when [selected], white with a 1 dp Border
 * outline otherwise, clipped to [shape]. Each chip keeps its own size, padding, content and touch
 * target, which differ from frame to frame.
 */
fun Modifier.choiceChipSurface(selected: Boolean, shape: Shape): Modifier =
    clip(shape)
        .background(if (selected) Ink else Bg)
        .then(if (selected) Modifier else Modifier.border(1.dp, Border, shape))

/**
 * Each interest code's label (#230), in [INTERESTS] order: the profile step's chips show them, and
 * so can any screen that shows a student's interests. The stored codes never change with a label.
 */
val INTEREST_LABELS: Map<String, Int> =
    mapOf(
        "sports" to R.string.interest_sports,
        "outdoors" to R.string.interest_outdoors,
        "music" to R.string.interest_music,
        "parties" to R.string.interest_parties,
        "cinema" to R.string.interest_cinema,
        "games" to R.string.interest_games,
        "photography" to R.string.interest_photography,
        "cooking" to R.string.interest_cooking,
        "tech" to R.string.interest_tech,
        "startups" to R.string.interest_startups,
        "arts" to R.string.interest_arts,
        "volunteering" to R.string.interest_volunteering,
    )

/** The label of the interest [code], or `null` for a code not in [INTERESTS]. */
@StringRes fun interestLabel(code: String): Int? = INTEREST_LABELS[code]
