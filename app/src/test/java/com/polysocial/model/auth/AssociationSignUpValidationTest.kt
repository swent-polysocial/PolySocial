// Contributors: OpenAI Codex (association signup validation and student policy regression tests,
// #125).
package com.polysocial.model.auth

import com.polysocial.model.user.AccountType
import org.junit.Assert.*
import org.junit.Test

class AssociationSignUpValidationTest {
  private val association =
      SignUpForm(
          email = "club@example.org",
          password = "testpass1",
          confirmPassword = "testpass1",
          accountType = AccountType.ASSOCIATION,
      )

  @Test
  fun associationAcceptsAnySyntacticallyValidDomainWithoutAPersonalName() {
    for (email in
        listOf(
            "club@example.org",
            "club@example.ch",
            "club@epfl.ch",
            "club@unit.epfl.ch",
            " club+events@sub.example.org ",
        )) {
      val form = association.copy(email = email)
      assertNull(email, form.error(SignUpField.Email))
      assertNull(form.error(SignUpField.FullName))
      assertTrue(email, form.isValid)
    }
  }

  @Test
  fun malformedAssociationMailboxDoesNotUseTheStudentDomainError() {
    for (email in
        listOf(
            "club.example.org",
            "club@@example.org",
            "club@",
            "@example.org",
            "club@localhost",
            "club@example..org",
            "club name@example.org",
            "club@-example.org",
        )) {
      val form = association.copy(email = email)
      assertEquals(email, FieldError.InvalidEmail, form.error(SignUpField.Email))
      assertFalse(email, form.isValid)
    }
  }

  @Test
  fun blankMailboxRemainsRequired() {
    for (email in listOf("", "  ", "\n")) {
      val form = association.copy(email = email)
      assertEquals(FieldError.Required, form.error(SignUpField.Email))
      assertFalse(form.isValid)
    }
  }

  @Test
  fun passwordAndConfirmationRulesApplyToAssociations() {
    assertEquals(FieldError.Required, association.copy(password = "").error(SignUpField.Password))
    assertEquals(
        FieldError.PasswordRules,
        association.copy(password = "short1").error(SignUpField.Password),
    )
    assertEquals(
        FieldError.PasswordRules,
        association.copy(password = "longpassword").error(SignUpField.Password),
    )
    assertEquals(
        FieldError.Required,
        association.copy(confirmPassword = "").error(SignUpField.ConfirmPassword),
    )
    val mismatch = association.copy(confirmPassword = "different1")
    assertEquals(FieldError.PasswordMismatch, mismatch.error(SignUpField.ConfirmPassword))
    assertFalse(mismatch.isValid)
    assertTrue(association.isValid)
  }

  @Test
  fun studentsStillRequireTheirPersonalNameAndExactEpflDomain() {
    val student = association.copy(accountType = AccountType.STUDENT)
    assertEquals(FieldError.Required, student.error(SignUpField.FullName))
    assertEquals(FieldError.InvalidDomain, student.error(SignUpField.Email))
    assertFalse(student.isValid)
    assertTrue(student.copy(fullName = "Test Student", email = "student@epfl.ch").isValid)
    assertEquals(
        FieldError.InvalidDomain,
        student.copy(email = "student@unit.epfl.ch").error(SignUpField.Email),
    )
  }

  @Test
  fun omittedAccountTypeKeepsStudentPolicy() {
    val form =
        SignUpForm(
            fullName = "Test Student",
            email = "club@example.org",
            password = "testpass1",
            confirmPassword = "testpass1",
        )
    assertEquals(AccountType.STUDENT, form.accountType)
    assertEquals(FieldError.InvalidDomain, form.error(SignUpField.Email))
    assertFalse(form.isValid)
  }
}
