// Contributors: Claude (Sonnet 5.5, Anthropic) (wrote these tests of the Directions button's state,
// #391).
package com.polysocial.ui.event.detail

import com.polysocial.model.directions.FakeDirectionsLauncher
import com.polysocial.model.event.Coordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectionsViewModelTest {

  private val launcher = FakeDirectionsLauncher()
  private val viewModel = DirectionsViewModel(launcher)
  private val place = Coordinates(46.5191, 6.5668)

  @Test
  fun tappingOpensDirectionsToThePlace_andSaysNothing() {
    viewModel.onDirections(place)

    assertEquals(listOf(place), launcher.opened)
    assertNull(viewModel.unavailable.value)
  }

  @Test
  fun withNoAppToOpenThem_itReportsTheMessage() {
    launcher.canOpen = false

    viewModel.onDirections(place)

    assertEquals(DirectionsUnavailable(0), viewModel.unavailable.value)
  }

  @Test
  fun aSecondFailureIsAMessageOfItsOwn() {
    launcher.canOpen = false
    viewModel.onDirections(place)
    val first = requireNotNull(viewModel.unavailable.value)

    viewModel.onDirections(place)

    assertNotEquals(first, viewModel.unavailable.value)
  }

  @Test
  fun aShownMessageIsCleared() {
    launcher.canOpen = false
    viewModel.onDirections(place)

    viewModel.onUnavailableShown(requireNotNull(viewModel.unavailable.value))

    assertNull(viewModel.unavailable.value)
  }

  @Test
  fun aLateShownDoesNotClearANewerMessage() {
    launcher.canOpen = false
    viewModel.onDirections(place)
    val first = requireNotNull(viewModel.unavailable.value)
    viewModel.onDirections(place)
    val second = requireNotNull(viewModel.unavailable.value)

    viewModel.onUnavailableShown(first)

    assertEquals(second, viewModel.unavailable.value)
  }

  @Test
  fun aTapThatWorksAfterAFailureDropsTheOldMessage() {
    launcher.canOpen = false
    viewModel.onDirections(place)
    launcher.canOpen = true

    viewModel.onDirections(place)

    assertNull(viewModel.unavailable.value)
  }
}
