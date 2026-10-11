// Contributors: Claude (category icon tiles from the Figma "Foundations" rule, #105).
package com.polysocial.ui.event

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.polysocial.R
import com.polysocial.model.event.EventCategory
import com.polysocial.ui.theme.AccentSoft
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Info
import com.polysocial.ui.theme.InfoSoft
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Success
import com.polysocial.ui.theme.SuccessSoft
import com.polysocial.ui.theme.Surface
import com.polysocial.ui.theme.Warning
import com.polysocial.ui.theme.WarningSoft

/**
 * How an [EventCategory] looks wherever events are shown. Figma's rule: party is red, sports green,
 * culture blue and study amber, and a color never appears alone, so the tile always has an icon and
 * the category's [label] is shown or read next to it.
 *
 * @property color the icon's color.
 * @property tileColor the light background of the icon tile.
 */
data class EventCategoryStyle(
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
    val color: Color,
    val tileColor: Color,
)

/** The style of this category. [EventCategory.OTHER] has no Figma color, so it stays neutral. */
val EventCategory.style: EventCategoryStyle
  get() =
      when (this) {
        EventCategory.PARTY ->
            EventCategoryStyle(
                R.drawable.ic_category_party,
                R.string.event_category_party,
                AccentText,
                AccentSoft,
            )
        EventCategory.SPORTS ->
            EventCategoryStyle(
                R.drawable.ic_category_sports,
                R.string.event_category_sports,
                Success,
                SuccessSoft,
            )
        EventCategory.CULTURE ->
            EventCategoryStyle(
                R.drawable.ic_category_culture,
                R.string.event_category_culture,
                Info,
                InfoSoft,
            )
        EventCategory.STUDY ->
            EventCategoryStyle(
                R.drawable.ic_category_study,
                R.string.event_category_study,
                Warning,
                WarningSoft,
            )
        EventCategory.OTHER ->
            EventCategoryStyle(
                R.drawable.ic_tab_events,
                R.string.event_category_other,
                Ink2,
                Surface,
            )
      }
