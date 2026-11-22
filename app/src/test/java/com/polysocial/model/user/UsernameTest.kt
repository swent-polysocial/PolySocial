// Contributors: Claude (Opus 5.5, Anthropic) (wrote these tests for the username format, #448).
package com.polysocial.model.user

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The username format decided in #366 and by the Sprint 6 PO (#445): a lowercase letter, then
 * lowercase letters, digits, `_` or `.`, 3 to 20 characters in all, and none of the reserved names.
 * The `usernames/{name}` Security Rule refuses the same names.
 */
class UsernameTest {

  @Test
  fun theLimitsAreThreeAndTwentyCharacters() {
    assertEquals(3, MIN_USERNAME_LENGTH)
    assertEquals(20, MAX_USERNAME_LENGTH)
  }

  @Test
  fun namesWithTheAllowedCharacters_areAccepted() {
    for (name in listOf("abc", "franek", "lea.martin", "tom_rossi", "a1b2", "x__", "z..")) {
      assertNull(name, usernameError(name))
    }
  }

  @Test
  fun namesAtTheLimits_areAccepted() {
    assertNull(usernameError("a".repeat(MIN_USERNAME_LENGTH)))
    assertNull(usernameError("a".repeat(MAX_USERNAME_LENGTH)))
  }

  @Test
  fun namesOutsideTheLimits_areRefused() {
    assertEquals(UsernameError.FORMAT, usernameError(""))
    assertEquals(UsernameError.FORMAT, usernameError("ab"))
    assertEquals(UsernameError.FORMAT, usernameError("a".repeat(MAX_USERNAME_LENGTH + 1)))
  }

  @Test
  fun aNameNotStartingWithALetter_isRefused() {
    for (name in listOf("9franek", "_franek", ".franek")) {
      assertEquals(name, UsernameError.FORMAT, usernameError(name))
    }
  }

  @Test
  fun aNameWithAnotherCharacter_isRefused() {
    for (name in listOf("Franek", "fra nek", "franek!", "fra-nek", "@franek", "françois", "ﬁnn")) {
      assertEquals(name, UsernameError.FORMAT, usernameError(name))
    }
  }

  @Test
  fun everyReservedName_isRefused() {
    assertEquals(
        setOf(
            "polysocial",
            "admin",
            "support",
            "epfl",
            "agepoly",
            "staff",
            "moderator",
            "official",
            "help",
        ),
        RESERVED_USERNAMES,
    )
    for (name in RESERVED_USERNAMES) assertEquals(name, UsernameError.RESERVED, usernameError(name))
  }

  @Test
  fun aNameThatOnlyContainsAReservedOne_isAccepted() {
    assertNull(usernameError("epfl.fan"))
    assertNull(usernameError("helper"))
  }

  @Test
  fun normalizing_dropsOneAtAndTheSpacesAndLowercases() {
    assertEquals("franek", normalizeUsername("  @Franek "))
    assertEquals("lea.martin", normalizeUsername("LEA.MARTIN"))
    assertEquals("@franek", normalizeUsername("@@franek"))
    assertEquals("", normalizeUsername(" @ "))
  }

  @Test
  fun normalizing_ignoresTheDeviceLocale() {
    // In Turkish, "I" lowercases to a dotless ı, which the format refuses.
    val deviceLocale = Locale.getDefault()
    Locale.setDefault(Locale.forLanguageTag("tr"))
    try {
      assertEquals("ilyas", normalizeUsername("ILYAS"))
    } finally {
      Locale.setDefault(deviceLocale)
    }
  }
}
