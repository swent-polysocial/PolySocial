// Contributors: Claude (wrote these tests, #134; additional tests from a test audit, #134;
// capitals and Unicode spaces, #134).
package com.polysocial.ui.event.create

import org.junit.Assert.assertEquals
import org.junit.Test

/** The association avatar's initials on the Create Event form (#134), as in Figma: "AG". */
class OrganizerInitialsTest {

  @Test
  fun aOneWordName_givesItsFirstTwoLetters() {
    assertEquals("AG", organizerInitialsOf("AGEPoly"))
    assertEquals("PO", organizerInitialsOf("polyjapan"))
  }

  @Test
  fun aNameOfSeveralWords_givesTheFirstLettersOfItsFirstAndLastWords() {
    assertEquals("FB", organizerInitialsOf("Fréquence Banane"))
    assertEquals("CL", organizerInitialsOf("Club de ski de l'EPFL"))
  }

  @Test
  fun extraSpaces_areIgnored() {
    assertEquals("SC", organizerInitialsOf("  ski \t  club  "))
    assertEquals("SK", organizerInitialsOf("  ski  "))
  }

  @Test
  fun accentedLetters_areCapitalized() {
    assertEquals("ÉC", organizerInitialsOf("éclair"))
    assertEquals("ÉÂ", organizerInitialsOf("éclair à la crème avec Ândré"))
  }

  @Test
  fun capitalizingNeverAddsLetters() {
    // "ß" has no one-letter capital: uppercase() would turn it into "SS", three initials in all.
    assertEquals("ßK", organizerInitialsOf("ßki"))
    assertEquals("ßC", organizerInitialsOf("ßki club"))
  }

  @Test
  fun anyUnicodeSpace_separatesWords() {
    assertEquals("SC", organizerInitialsOf("Ski\u00A0Club"))
    assertEquals("SC", organizerInitialsOf("\u2003Ski\u202FClub\u00A0"))
  }

  @Test
  fun aOneLetterName_givesThatLetter() {
    assertEquals("X", organizerInitialsOf("x"))
  }

  @Test
  fun aCharacterOutsideTheBasicPlane_isNeverCutInHalf() {
    // An emoji is two UTF-16 units: taking two units would leave half of it.
    assertEquals("🎉P", organizerInitialsOf("🎉Party"))
    assertEquals("🎉🎵", organizerInitialsOf("🎉 🎵"))
  }

  @Test
  fun aBlankName_givesNoInitials() {
    assertEquals("", organizerInitialsOf(""))
    assertEquals("", organizerInitialsOf("   "))
  }

  @Test
  fun digitsAndPunctuation_countAsTheyAre_andOnlySpacesSeparateWords() {
    assertEquals("4L", organizerInitialsOf("42 Lausanne"))
    assertEquals("CL", organizerInitialsOf("Club-Ski"))
    assertEquals("SL", organizerInitialsOf("Ski-Club Lausanne"))
  }
}
