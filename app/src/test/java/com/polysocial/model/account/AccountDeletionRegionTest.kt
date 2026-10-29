// Contributors: Claude (Opus 5.5, Anthropic) wrote this consistency test for #206; Mohamed Khellaf
// (reviewed and revised).
package com.polysocial.model.account

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Keeps the client's region and function name in line with the Cloud Function's source, so a change
 * on one side can't make the callable unreachable. Unit tests run from `app/`.
 */
class AccountDeletionRegionTest {
  private val functions = File("../firebase/functions")

  /** The file's text, or skips the test while the function's PR (#267) has not merged. */
  private fun source(name: String): String {
    val file = File(functions, name)
    // The Cloud Function arrives with #267; skip until then instead of failing or ignoring.
    assumeTrue("$name not on this branch yet (#267)", file.exists())
    return file.readText()
  }

  @Test
  fun clientRegion_matchesTheFunctionsRegion() {
    val region =
        Regex("""const REGION = "([^"]+)"""")
            .find(source("deleteAccountHandler.js"))
            ?.groupValues
            ?.get(1)

    assertEquals(ACCOUNT_DELETION_REGION, region)
  }

  @Test
  fun clientFunctionName_isExported() {
    val exported =
        Regex("""exports\.(\w+)\s*=\s*onCall""")
            .findAll(source("index.js"))
            .map { it.groupValues[1] }
            .toList()

    assertTrue("exported callables: $exported", DELETE_ACCOUNT_FUNCTION in exported)
  }
}
