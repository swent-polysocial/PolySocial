// Contributors: Claude (wrote these tests; the place name tests, #112); Mohamed Khellaf
// (reviewed). Claude (filling the form from an event to edit it, #131).
package com.polysocial.ui.event.create

import com.polysocial.model.event.Coordinates
import com.polysocial.model.event.EventCategory
import com.polysocial.model.event.MAX_DESCRIPTION_LENGTH
import com.polysocial.model.event.MAX_LOCATION_NAME_LENGTH
import com.polysocial.model.event.MAX_TITLE_LENGTH
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lausanne's time zone, so the conversions match what students see. */
val ZURICH: ZoneId = ZoneId.of("Europe/Zurich")

/** Wednesday 7 October 2026, 12:00 in Lausanne. */
val FORM_NOW: Instant = Instant.parse("2026-10-07T10:00:00Z")

val ROLEX = PickedLocation(Coordinates(46.5184, 6.5683), "Rolex Learning Center")

/** A form that can be submitted: Saturday 10 October, 14:00 to 16:00. */
fun completeForm() =
    CreateEventForm(
        title = "Study together",
        description = "Exercise sheet 4",
        category = EventCategory.STUDY,
        date = LocalDate.of(2026, 10, 10),
        startTime = LocalTime.of(14, 0),
        endTime = LocalTime.of(16, 0),
        location = ROLEX,
        capacityText = "8",
        isPrivate = true,
    )

class CreateEventFormTest {

  @Test
  fun toEvent_mapsEveryFieldInLausanneTime() {
    val event = completeForm().toEvent(ZURICH)!!

    assertEquals("Study together", event.title)
    assertEquals("Exercise sheet 4", event.description)
    assertEquals(EventCategory.STUDY, event.category)
    assertEquals(ROLEX.coordinates, event.location)
    assertEquals(Instant.parse("2026-10-10T12:00:00Z"), event.startTime)
    assertEquals(Instant.parse("2026-10-10T14:00:00Z"), event.endTime)
    assertEquals(8, event.capacity)
    assertTrue(event.isPrivate)
  }

  @Test
  fun toEvent_putsAnEndBeforeTheStartOnTheNextDay() {
    val form = completeForm().copy(startTime = LocalTime.of(22, 0), endTime = LocalTime.of(2, 0))

    val event = form.toEvent(ZURICH)!!

    assertTrue(form.endsNextDay)
    assertEquals(Instant.parse("2026-10-10T20:00:00Z"), event.startTime)
    assertEquals(Instant.parse("2026-10-11T00:00:00Z"), event.endTime)
  }

  @Test
  fun endsNextDay_isFalseForASameDayEndOrNoEnd() {
    assertFalse(completeForm().endsNextDay)
    assertFalse(completeForm().copy(endTime = null).endsNextDay)
  }

  @Test
  fun toEvent_leavesOptionalFieldsEmpty() {
    val event = completeForm().copy(endTime = null, capacityText = " ").toEvent(ZURICH)!!

    assertNull(event.endTime)
    assertNull(event.capacity)
  }

  @Test
  fun toEvent_returnsNullWhileARequiredFieldIsMissingOrCapacityIsNotANumber() {
    assertNull(completeForm().copy(category = null).toEvent(ZURICH))
    assertNull(completeForm().copy(date = null).toEvent(ZURICH))
    assertNull(completeForm().copy(startTime = null).toEvent(ZURICH))
    assertNull(completeForm().copy(location = null).toEvent(ZURICH))
    assertNull(completeForm().copy(capacityText = "eight").toEvent(ZURICH))
  }

  @Test
  fun errors_areEmptyForACompleteForm() {
    assertEquals(emptySet<CreateEventFormError>(), completeForm().errors(FORM_NOW, ZURICH))
  }

  @Test
  fun errors_listTheMissingRequiredFieldsOfAnEmptyForm() {
    assertEquals(
        setOf(
            CreateEventFormError.MISSING_TITLE,
            CreateEventFormError.MISSING_CATEGORY,
            CreateEventFormError.MISSING_DATE,
            CreateEventFormError.MISSING_LOCATION,
        ),
        CreateEventForm().errors(FORM_NOW, ZURICH),
    )
  }

  @Test
  fun errors_rejectAStartInThePast() {
    val form = completeForm().copy(date = LocalDate.of(2026, 10, 6))

    assertEquals(setOf(CreateEventFormError.START_IN_PAST), form.errors(FORM_NOW, ZURICH))
  }

  @Test
  fun errors_rejectAnEndEqualToTheStart() {
    val form = completeForm().copy(endTime = LocalTime.of(14, 0))

    assertEquals(setOf(CreateEventFormError.END_SAME_AS_START), form.errors(FORM_NOW, ZURICH))
  }

  @Test
  fun errors_rejectACapacityBelowTwo() {
    for (text in listOf("1", "0", "-3")) {
      assertEquals(
          "capacity $text",
          setOf(CreateEventFormError.CAPACITY_TOO_SMALL),
          completeForm().copy(capacityText = text).errors(FORM_NOW, ZURICH),
      )
    }
  }

  @Test
  fun errors_rejectACapacityThatIsNotAWholeNumber() {
    for (text in listOf("eight", "2.5")) {
      assertEquals(
          "capacity $text",
          setOf(CreateEventFormError.CAPACITY_NOT_A_NUMBER),
          completeForm().copy(capacityText = text).errors(FORM_NOW, ZURICH),
      )
    }
  }

  @Test
  fun errors_rejectTooLongTextEvenBeforeTheDateAndLocationArePicked() {
    val form =
        CreateEventForm(
            title = "a".repeat(MAX_TITLE_LENGTH + 1),
            description = "a".repeat(MAX_DESCRIPTION_LENGTH + 1),
        )

    val errors = form.errors(FORM_NOW, ZURICH)

    assertTrue(CreateEventFormError.TITLE_TOO_LONG in errors)
    assertTrue(CreateEventFormError.DESCRIPTION_TOO_LONG in errors)
  }

  @Test
  fun errors_checkTheTimesBeforeTheOtherFieldsArePicked() {
    val pastStart =
        CreateEventForm(date = LocalDate.of(2026, 10, 7), startTime = LocalTime.of(10, 0))
    val endAtStart =
        CreateEventForm(
            date = LocalDate.of(2026, 10, 10),
            startTime = LocalTime.of(14, 0),
            endTime = LocalTime.of(14, 0),
        )

    assertTrue(CreateEventFormError.START_IN_PAST in pastStart.errors(FORM_NOW, ZURICH))
    assertTrue(CreateEventFormError.END_SAME_AS_START in endAtStart.errors(FORM_NOW, ZURICH))
  }

  @Test
  fun toEvent_trimsTheTitleAndDescription() {
    val event =
        completeForm()
            .copy(title = "  Study together ", description = "\nExercise sheet 4  ")
            .toEvent(ZURICH)!!

    assertEquals("Study together", event.title)
    assertEquals("Exercise sheet 4", event.description)
  }

  @Test
  fun errors_countTheTitleLengthWithoutSurroundingSpaces() {
    val form = completeForm().copy(title = "a".repeat(MAX_TITLE_LENGTH) + "  ")

    assertEquals(emptySet<CreateEventFormError>(), form.errors(FORM_NOW, ZURICH))
  }

  @Test
  fun toEvent_copiesThePickedPlaceName() {
    assertEquals("Rolex Learning Center", completeForm().toEvent(ZURICH)!!.locationName)
  }

  @Test
  fun toEvent_trimsThePickedPlaceName() {
    val form = completeForm().copy(location = ROLEX.copy(name = "  Satellite, EPFL \n"))

    assertEquals("Satellite, EPFL", form.toEvent(ZURICH)!!.locationName)
  }

  @Test
  fun toEvent_shortensALongPlaceNameToTheLimit() {
    val name = "a".repeat(50) + "b".repeat(100)
    val form = completeForm().copy(location = ROLEX.copy(name = name))

    val stored = form.toEvent(ZURICH)!!.locationName

    assertEquals(MAX_LOCATION_NAME_LENGTH, stored.length)
    assertEquals(name.take(100), stored)
  }

  @Test
  fun toEvent_keepsAPlaceNameOfExactlyTheLimit() {
    val name = "x".repeat(99) + "y"
    val form = completeForm().copy(location = ROLEX.copy(name = name))

    assertEquals(name, form.toEvent(ZURICH)!!.locationName)
  }

  @Test
  fun toEvent_trimsBeforeShortening() {
    val name = "   " + "z".repeat(100)
    val form = completeForm().copy(location = ROLEX.copy(name = name))

    assertEquals("z".repeat(100), form.toEvent(ZURICH)!!.locationName)
  }

  @Test
  fun toEvent_neverSplitsAnEmojiWhenShortening() {
    // The emoji takes characters 100 and 101, so a plain cut would keep half of it.
    val name = "a".repeat(99) + "\uD83C\uDF89" + "b"
    val form = completeForm().copy(location = ROLEX.copy(name = name))

    assertEquals("a".repeat(99), form.toEvent(ZURICH)!!.locationName)
  }

  @Test
  fun toEvent_keepsAnEmojiThatFitsWhole() {
    val name = "a".repeat(98) + "\uD83C\uDF89" + "b"
    val form = completeForm().copy(location = ROLEX.copy(name = name))

    assertEquals("a".repeat(98) + "\uD83C\uDF89", form.toEvent(ZURICH)!!.locationName)
  }

  @Test
  fun toEvent_dropsASpaceLeftAtTheEndOfTheCut() {
    val name = "a".repeat(99) + " and more"
    val form = completeForm().copy(location = ROLEX.copy(name = name))

    assertEquals("a".repeat(99), form.toEvent(ZURICH)!!.locationName)
  }

  @Test
  fun toForm_thenToEvent_givesBackTheSameEvent() {
    val nextDayEnd =
        completeForm().copy(startTime = LocalTime.of(22, 0), endTime = LocalTime.of(1, 0))
    val noLimitPublic = completeForm().copy(endTime = null, capacityText = "", isPrivate = false)

    for (form in listOf(completeForm(), nextDayEnd, noLimitPublic)) {
      val event = form.toEvent(ZURICH)!!

      assertEquals(form, event.toForm(ZURICH))
      assertEquals(event, event.toForm(ZURICH).toEvent(ZURICH))
    }
  }

  @Test
  fun toForm_ofAnEventSavedWithoutAPlaceName_asksThePickerToLookItUpAgain() {
    val event = completeForm().toEvent(ZURICH)!!.copy(locationName = "")

    val location = event.toForm(ZURICH).location!!

    assertEquals(ROLEX.coordinates, location.coordinates)
    assertFalse(location.isNamed)
  }

  @Test
  fun errors_whenEditing_acceptOnlyTheKeptStartInThePast() {
    // Wednesday 7 October, 11:00 in Lausanne: an hour before FORM_NOW.
    val started =
        completeForm().copy(date = LocalDate.of(2026, 10, 7), startTime = LocalTime.of(11, 0))
    val keptStart = started.toEvent(ZURICH)!!.startTime

    assertTrue(CreateEventFormError.START_IN_PAST in started.errors(FORM_NOW, ZURICH))
    assertEquals(emptySet<CreateEventFormError>(), started.errors(FORM_NOW, ZURICH, keptStart))
    assertEquals(
        setOf(CreateEventFormError.START_IN_PAST),
        started.copy(startTime = LocalTime.of(10, 0)).errors(FORM_NOW, ZURICH, keptStart),
    )
  }
}
