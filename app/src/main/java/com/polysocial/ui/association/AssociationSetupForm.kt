// Contributors: OpenAI Codex (association setup validation and initials, #125).
package com.polysocial.ui.association

import com.polysocial.model.association.Association
import com.polysocial.model.association.AssociationType
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

enum class AssociationSetupField {
  Name,
  Type,
  About,
  Website,
}

enum class AssociationSetupError {
  Required,
  TooLong,
  InvalidWebsite,
}

/** Validates the trimmed values saved by #126, without accessing Android or the network. */
data class AssociationSetupForm(
    val name: String = "",
    val type: AssociationType? = null,
    val about: String = "",
    val website: String = "",
) {
  fun error(field: AssociationSetupField): AssociationSetupError? =
      when (field) {
        AssociationSetupField.Name -> requiredTextError(name, 60)
        AssociationSetupField.Type -> if (type == null) AssociationSetupError.Required else null
        AssociationSetupField.About -> requiredTextError(about, 1000)
        AssociationSetupField.Website ->
            when {
              website.isBlank() -> null
              website.trim().length > 200 -> AssociationSetupError.TooLong
              !isAssociationWebsite(website.trim()) -> AssociationSetupError.InvalidWebsite
              else -> null
            }
      }

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

private fun requiredTextError(value: String, maximum: Int): AssociationSetupError? =
    when {
      value.isBlank() -> AssociationSetupError.Required
      value.trim().length > maximum -> AssociationSetupError.TooLong
      else -> null
    }

private fun isAssociationWebsite(value: String): Boolean {
  if (value.any(Char::isWhitespace)) return false
  return try {
    val uri = URI(value)
    uri.scheme == "https" && !uri.host.isNullOrEmpty() && (uri.port == -1 || uri.port in 1..65535)
  } catch (_: URISyntaxException) {
    false
  }
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
