// Contributors: Claude (dateDigits, written for the M1 end-to-end test, #136; moved here to share
// it between the end-to-end tests, #276).
package com.polysocial.endtoend

import java.time.LocalDate
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

/**
 * [date] as the digits the date picker's text field expects, in the order of the device locale's
 * short date pattern (MMddyyyy in English (US)), as Material 3 reads it.
 */
internal fun dateDigits(date: LocalDate): String {
  val pattern =
      DateTimeFormatterBuilder.getLocalizedDateTimePattern(
          FormatStyle.SHORT,
          null,
          IsoChronology.INSTANCE,
          Locale.getDefault(),
      )
  return pattern
      .filter { it in "dMy" }
      .toList()
      .distinct()
      .joinToString("") {
        when (it) {
          'd' -> "%02d".format(date.dayOfMonth)
          'M' -> "%02d".format(date.monthValue)
          else -> "%04d".format(date.year)
        }
      }
}
