// Contributors: OpenAI Codex (shared association form validation for setup and Edit details, #125).
package com.polysocial.model.association

import java.net.URI
import java.net.URISyntaxException

enum class AssociationField {
  Name,
  Type,
  About,
  Website,
}

enum class AssociationFieldError {
  Required,
  TooLong,
  InvalidWebsite,
}

/**
 * Validates trimmed association values without Android or Firebase; shared by setup and editing.
 */
fun associationFormErrors(
    name: String,
    type: AssociationType?,
    about: String,
    website: String,
): Map<AssociationField, AssociationFieldError> = buildMap {
  fun required(field: AssociationField, value: String, maximum: Int) {
    when {
      value.isBlank() -> put(field, AssociationFieldError.Required)
      value.trim().length > maximum -> put(field, AssociationFieldError.TooLong)
    }
  }
  required(AssociationField.Name, name, MAX_ASSOCIATION_NAME_LENGTH)
  required(AssociationField.About, about, MAX_ASSOCIATION_ABOUT_LENGTH)
  if (type == null) put(AssociationField.Type, AssociationFieldError.Required)
  when {
    website.isBlank() -> Unit
    website.trim().length > MAX_ASSOCIATION_WEBSITE_LENGTH ->
        put(AssociationField.Website, AssociationFieldError.TooLong)
    !isAssociationWebsite(website.trim()) ->
        put(AssociationField.Website, AssociationFieldError.InvalidWebsite)
  }
}

/**
 * An optional website, when present, must be a usable HTTPS URL, including a host and valid port.
 */
fun isAssociationWebsite(value: String): Boolean {
  if (value.any(Char::isWhitespace)) return false
  return try {
    val uri = URI(value)
    uri.scheme == "https" && !uri.host.isNullOrEmpty() && (uri.port == -1 || uri.port in 1..65535)
  } catch (_: URISyntaxException) {
    false
  }
}
