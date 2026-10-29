// Contributors: OpenAI Codex (association setup validation and initials, #125).
package com.polysocial.ui.association

import com.polysocial.model.association.Association
import com.polysocial.model.association.AssociationType
import java.util.Locale

typealias AssociationSetupField = com.polysocial.model.association.AssociationField

typealias AssociationSetupError = com.polysocial.model.association.AssociationFieldError

/** Validates the trimmed values saved by #126, without accessing Android or the network. */
data class AssociationSetupForm(
    val name: String = "",
    val type: AssociationType? = null,
    val about: String = "",
    val website: String = "",
) {
  fun error(field: AssociationSetupField): AssociationSetupError? =
      com.polysocial.model.association.associationFormErrors(name, type, about, website)[field]

  val isValid: Boolean
    get() = AssociationSetupField.entries.all { error(it) == null }

  /** Call only after validation; the server supplies creation time. */
  fun association(uid: String): Association =
      Association(
          uid = uid,
          name = name.trim(),
          type = requireNotNull(type),
          about = about.trim(),
          website = website.trim().takeIf { it.isNotEmpty() },
      )
}

/** Two word initials, or the first two code points of one word; never splits a surrogate pair. */
fun associationInitials(name: String): String {
  val words = name.trim().split(Regex("[\\s\\p{Z}]+")).filter(String::isNotEmpty)
  val initials =
      when {
        words.isEmpty() -> ""
        words.size == 1 -> {
          val points = words[0].codePoints().toArray()
          String(points, 0, minOf(2, points.size))
        }
        else ->
            buildString {
              appendCodePoint(words[0].codePointAt(0))
              appendCodePoint(words[1].codePointAt(0))
            }
      }
  return initials.uppercase(Locale.ROOT)
}
