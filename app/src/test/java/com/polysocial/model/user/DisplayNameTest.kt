// Contributors: Claude (wrote these tests first, test-driven, for the full name's limit, #143).
package com.polysocial.model.user

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The full name's limit (#143), shared by sign-up and the profile step and matched by the
 * `users/{uid}` rule: at most 50 characters of the trimmed name, counted in UTF-16 units like the
 * rule's `size()` (checked on the Firestore emulator): an emoji counts as 2.
 */
class DisplayNameTest {

  @Test
  fun theLimitIsFiftyCharacters() {
    assertEquals(50, MAX_DISPLAY_NAME_LENGTH)
  }

  @Test
  fun aNameAtTheLimit_isAccepted() {
    assertFalse(isDisplayNameTooLong("a".repeat(50)))
  }

  @Test
  fun aNameOneOverTheLimit_isRefused() {
    assertTrue(isDisplayNameTooLong("a".repeat(51)))
  }

  @Test
  fun aShortName_isAccepted() {
    assertFalse(isDisplayNameTooLong("Franek Najda"))
  }

  @Test
  fun anEmptyOrBlankName_isNotTooLong() {
    // A blank name is refused as missing, not as too long.
    assertFalse(isDisplayNameTooLong(""))
    assertFalse(isDisplayNameTooLong(" ".repeat(80)))
  }

  @Test
  fun spacesAroundTheName_dontCount_becauseTheNameIsSavedTrimmed() {
    assertFalse(isDisplayNameTooLong("  " + "a".repeat(50) + "   "))
    assertTrue(isDisplayNameTooLong("  " + "a".repeat(51) + "   "))
  }

  @Test
  fun spacesInsideTheName_count() {
    // 25 + 1 + 25 = 51 characters once trimmed.
    assertTrue(isDisplayNameTooLong("a".repeat(25) + " " + "a".repeat(25)))
  }

  @Test
  fun anEmojiCountsAsTwo_likeTheRule() {
    val emoji = "\uD83D\uDE00" // 😀, two UTF-16 units
    assertFalse(isDisplayNameTooLong(emoji.repeat(25)))
    assertTrue(isDisplayNameTooLong(emoji.repeat(26)))
  }

  @Test
  fun anAccentedLetterCountsAsOneCharacter() {
    assertFalse(isDisplayNameTooLong("é".repeat(50)))
    assertTrue(isDisplayNameTooLong("é".repeat(51)))
  }
}
