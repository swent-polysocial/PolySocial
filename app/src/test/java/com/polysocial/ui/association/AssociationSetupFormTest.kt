// Contributors: OpenAI Codex (association form boundaries, mapping and initials tests, #125).
package com.polysocial.ui.association

import com.polysocial.model.association.AssociationType
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationSetupFormTest {
  private val valid =
      AssociationSetupForm("Example Club", AssociationType.CLUB, "A fictional campus club.")

  @Test
  fun emptyRequiredFieldsReportRequiredAndBlockSubmission() {
    val form = AssociationSetupForm()
    for (field in
        listOf(
            AssociationSetupField.Name,
            AssociationSetupField.Type,
            AssociationSetupField.About,
        )) {
      assertEquals(AssociationSetupError.Required, form.error(field))
    }
    assertNull(form.error(AssociationSetupField.Website))
    assertFalse(form.isValid)
  }

  @Test
  fun blankRequiredTextIsRefused() {
    assertEquals(
        AssociationSetupError.Required,
        valid.copy(name = " \t\n").error(AssociationSetupField.Name),
    )
    assertEquals(
        AssociationSetupError.Required,
        valid.copy(about = "\u00a0").error(AssociationSetupField.About),
    )
    assertFalse(valid.copy(type = null).isValid)
  }

  @Test
  fun nameAndAboutAcceptTheExactLimitAndRefuseOneMore() {
    assertTrue(valid.copy(name = "a".repeat(60), about = "b".repeat(1000)).isValid)
    assertEquals(
        AssociationSetupError.TooLong,
        valid.copy(name = "a".repeat(61)).error(AssociationSetupField.Name),
    )
    assertEquals(
        AssociationSetupError.TooLong,
        valid.copy(about = "b".repeat(1001)).error(AssociationSetupField.About),
    )
    assertFalse(valid.copy(name = "a".repeat(61)).isValid)
    assertFalse(valid.copy(about = "b".repeat(1001)).isValid)
  }

  @Test
  fun lengthUsesTrimmedUtf16UnitsAsTheStoredDocumentDoes() {
    assertTrue(valid.copy(name = "  " + "a".repeat(60) + "  ").isValid)
    assertTrue(valid.copy(name = "😀".repeat(30)).isValid)
    assertEquals(
        AssociationSetupError.TooLong,
        valid.copy(name = "😀".repeat(31)).error(AssociationSetupField.Name),
    )
  }

  @Test
  fun optionalWebsiteAcceptsBlankAndHttpsUrlsWithHosts() {
    for (website in
        listOf(
            "",
            " \t",
            "https://example.org",
            " https://example.org/club?q=1#about ",
            "https://example.org:443",
        )) {
      assertTrue("Expected valid website: $website", valid.copy(website = website).isValid)
    }
  }

  @Test
  fun websiteRejectsInvalidOrInsecureAddresses() {
    for (website in
        listOf(
            "example.org",
            "http://example.org",
            "HTTPS://example.org",
            "https://",
            "https:///path",
            "https://bad host.org",
            "https://example.org/with\nnewline",
            "https://example.org/%xx",
            "https://example.org:0",
            "https://example.org:65536",
        )) {
      val form = valid.copy(website = website)
      assertEquals(
          website,
          AssociationSetupError.InvalidWebsite,
          form.error(AssociationSetupField.Website),
      )
      assertFalse(form.isValid)
    }
  }

  @Test
  fun websiteAcceptsTwoHundredCharactersAndRejectsTwoHundredOne() {
    val prefix = "https://example.org/"
    val website = prefix + "a".repeat(200 - prefix.length)
    assertTrue(valid.copy(website = website).isValid)
    assertEquals(
        AssociationSetupError.TooLong,
        valid.copy(website = website + "a").error(AssociationSetupField.Website),
    )
  }

  @Test
  fun allAssociationTypesCanBeSubmitted() {
    for (type in AssociationType.entries) assertTrue(valid.copy(type = type).isValid)
  }

  @Test
  fun mappingTrimsFieldsAndKeepsOnlyThePublicAssociationData() {
    val association =
        valid
            .copy(
                name = " Example Club ",
                about = " Example description ",
                website = " https://example.org ",
            )
            .association("test-association")
    assertEquals("test-association", association.uid)
    assertEquals("Example Club", association.name)
    assertEquals(AssociationType.CLUB, association.type)
    assertEquals("Example description", association.about)
    assertEquals("https://example.org", association.website)
    assertNull(association.createdAt)
    assertNull(valid.copy(website = "  ").association("test-association").website)
  }

  @Test(expected = IllegalArgumentException::class)
  fun mappingWithoutARequiredTypeFailsFast() {
    valid.copy(type = null).association("test-association")
  }

  @Test
  fun initialsHandleEmptySingleWordAndMultipleWords() {
    assertEquals("", associationInitials(" \t\n"))
    assertEquals("A", associationInitials("a"))
    assertEquals("EX", associationInitials("example"))
    assertEquals("EC", associationInitials("  Example   Club and more "))
    assertEquals("EC", associationInitials("Example\tClub"))
    assertEquals("EC", associationInitials("Example\u00a0Club"))
  }

  @Test
  fun initialsPreserveSupplementaryCodePoints() {
    assertEquals("😀A", associationInitials("😀alpha"))
    assertEquals("😀", associationInitials("😀"))
    assertEquals("😀🛰", associationInitials("😀alpha 🛰beta"))
  }

  @Test
  fun initialsAreIndependentOfTheDeviceLocale() {
    val previous = Locale.getDefault()
    try {
      Locale.setDefault(Locale.forLanguageTag("tr"))
      assertEquals("IC", associationInitials("imaginary club"))
    } finally {
      Locale.setDefault(previous)
    }
  }
}
