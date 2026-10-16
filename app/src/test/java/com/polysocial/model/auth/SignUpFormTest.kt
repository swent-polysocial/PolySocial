// Contributors: Claude (wrote these tests first, test-driven, for the full name's limit, #143).
package com.polysocial.model.auth

import com.polysocial.model.user.MAX_DISPLAY_NAME_LENGTH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sign-up's full name check against the shared limit (#143). */
class SignUpFormTest {

  private val valid =
      SignUpForm(
          fullName = "Test Student",
          email = "student.test@epfl.ch",
          password = "password1",
          confirmPassword = "password1",
      )

  @Test
  fun aFullNameOverTheLimit_isTooLongAndTheFormIsInvalid() {
    val form = valid.copy(fullName = "a".repeat(MAX_DISPLAY_NAME_LENGTH + 1))

    assertEquals(FieldError.TooLong, form.error(SignUpField.FullName))
    assertFalse(form.isValid)
  }

  @Test
  fun aFullNameAtTheLimit_hasNoError() {
    val form = valid.copy(fullName = "a".repeat(MAX_DISPLAY_NAME_LENGTH))

    assertNull(form.error(SignUpField.FullName))
    assertTrue(form.isValid)
  }

  @Test
  fun spacesAroundTheFullName_dontCount() {
    val form = valid.copy(fullName = "  " + "a".repeat(MAX_DISPLAY_NAME_LENGTH) + "  ")

    assertNull(form.error(SignUpField.FullName))
  }

  @Test
  fun emojiCountAsTwo_likeTheRule() {
    val emoji = "\uD83D\uDE00"

    assertNull(valid.copy(fullName = emoji.repeat(25)).error(SignUpField.FullName))
    assertEquals(
        FieldError.TooLong,
        valid.copy(fullName = emoji.repeat(26)).error(SignUpField.FullName),
    )
  }

  @Test
  fun aBlankFullName_isStillRequired_notTooLong() {
    assertEquals(
        FieldError.Required,
        valid.copy(fullName = " ".repeat(80)).error(SignUpField.FullName),
    )
  }

  @Test
  fun theLimitOnlyConcernsTheFullName() {
    val form = valid.copy(fullName = "a".repeat(MAX_DISPLAY_NAME_LENGTH + 1))

    assertNull(form.error(SignUpField.Email))
    assertNull(form.error(SignUpField.Password))
    assertNull(form.error(SignUpField.ConfirmPassword))
  }
}
