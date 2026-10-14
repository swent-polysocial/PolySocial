// Contributors: Claude (location picker screen from the Figma "Pick location" frames, section 04,
// #111).
package com.polysocial.ui.event.create

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polysocial.R
import com.polysocial.model.event.Coordinates
import com.polysocial.model.geocoding.Place
import com.polysocial.ui.theme.AccentSoft
import com.polysocial.ui.theme.AccentText
import com.polysocial.ui.theme.Bg
import com.polysocial.ui.theme.Ink
import com.polysocial.ui.theme.Ink2
import com.polysocial.ui.theme.Ink3
import com.polysocial.ui.theme.MapLand
import com.polysocial.ui.theme.PlusJakartaSans

/** Test tags of the location picker. */
object LocationPickerTags {
  const val SCREEN = "location_picker"
  const val MAP = "location_picker_map"
  const val BACK = "location_picker_back"
  const val QUERY = "location_picker_query"
  const val SEARCH = "location_picker_search"
  const val HINT = "location_picker_hint"
  const val RESULTS = "location_picker_results"
  const val SEARCHING = "location_picker_searching"
  const val BANNER = "location_picker_banner"
  const val BANNER_ACTION = "location_picker_banner_action"
  const val PIN = "location_picker_pin"
  const val MY_LOCATION = "location_picker_my_location"
  const val CARD = "location_picker_card"
  const val USE = "location_picker_use"

  fun result(index: Int) = "location_picker_result_$index"
}

/**
 * Draws the map under the picker: it starts on [CameraRequest.target], moves to every new
 * [CameraRequest], reports where its center stops and when it is tapped, and keeps its own credits
 * above the given bottom inset.
 */
typealias PickerMapRenderer =
    @Composable
    (
        camera: CameraRequest,
        onCenterSettled: (Coordinates) -> Unit,
        onMapTap: () -> Unit,
        bottomInset: Dp,
    ) -> Unit

/** What the location picker reports. */
data class LocationPickerActions(
    val onBack: () -> Unit,
    val onQueryChange: (String) -> Unit,
    val onSearch: () -> Unit,
    val onResultSelect: (Place) -> Unit,
    val onAllowInSettings: () -> Unit,
    val onMyLocation: () -> Unit,
    val onUseLocation: () -> Unit,
    val onCenterSettled: (Coordinates) -> Unit,
    val onMapTap: () -> Unit,
)

/**
 * The Create Event location picker (#111): opens on [initial] (the form's place), on the student's
 * position, or on Lausanne, and reports the confirmed place to [onPicked]. [onBack] leaves without
 * changing the form. Back first closes the search results or message, if any.
 *
 * "Allow in Settings" opens the app's settings, and the picker reads the permission again when the
 * student comes back. Without a Mapbox token, the map is a plain background and the pin, search and
 * Confirm still work.
 */
@Composable
fun LocationPickerRoute(
    initial: PickedLocation?,
    onBack: () -> Unit,
    onPicked: (PickedLocation) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LocationPickerViewModel = hiltViewModel(),
    renderer: PickerMapRenderer = defaultPickerRenderer(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val unnamedName = stringResource(R.string.location_picker_dropped_pin)
  LaunchedEffect(viewModel) { viewModel.open(initial) }
  LaunchedEffect(state.confirmed) {
    if (state.confirmed) onPicked(viewModel.selection(unnamedName))
  }
  DisposableEffect(lifecycleOwner, viewModel) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) viewModel.onLocationAccessCheck()
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  BackHandler {
    if (state.search != PlaceSearchState.Idle) viewModel.onSearchDismiss() else onBack()
  }
  LocationPickerScreen(
      state = state,
      actions =
          LocationPickerActions(
              onBack = onBack,
              onQueryChange = viewModel::onQueryChange,
              onSearch = viewModel::onSearch,
              onResultSelect = viewModel::onResultSelect,
              onAllowInSettings = {
                context.startActivity(
                    Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
              },
              onMyLocation = viewModel::onMyLocation,
              onUseLocation = viewModel::onUseLocation,
              onCenterSettled = viewModel::onPinMove,
              onMapTap = { if (state.search != PlaceSearchState.Idle) viewModel.onSearchDismiss() },
          ),
      renderer = renderer,
      modifier = modifier,
  )
}

/** The Mapbox map, or a plain background when this build has no Mapbox token. */
@Composable
private fun defaultPickerRenderer(): PickerMapRenderer {
  val hasToken = stringResource(R.string.mapbox_access_token).startsWith("pk.")
  return if (hasToken) {
    { camera, onCenterSettled, onMapTap, bottomInset ->
      PickerMapboxRenderer(camera, onCenterSettled, onMapTap, bottomInset)
    }
  } else {
    { _, _, _, _ -> Box(Modifier.fillMaxSize().background(MapLand)) }
  }
}

/**
 * The picker drawn from [state] alone, so every Figma state can be shown and tested without a
 * ViewModel or the native map. Positions follow the 360 × 800 Figma frames: the search bar and its
 * panel at the top, the pin at the center of the map, the place card at the bottom.
 */
@Composable
fun LocationPickerScreen(
    state: LocationPickerUiState,
    actions: LocationPickerActions,
    renderer: PickerMapRenderer,
    modifier: Modifier = Modifier,
) {
  val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
  val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
  Box(modifier.fillMaxSize().background(MapLand).testTag(LocationPickerTags.SCREEN)) {
    renderer(state.camera, actions.onCenterSettled, actions.onMapTap, bottom + MAP_CREDITS_BOTTOM)
    // The pin's tip and its shadow sit on the map's center, where the picked spot is.
    Image(
        painterResource(R.drawable.ic_picker_pin_shadow),
        contentDescription = null,
        modifier = Modifier.align(Alignment.Center).offset(y = (-4).dp),
    )
    Image(
        painterResource(R.drawable.ic_picker_pin),
        contentDescription = null,
        modifier =
            Modifier.align(Alignment.Center).offset(y = (-36).dp).testTag(LocationPickerTags.PIN),
    )
    Column(
        Modifier.align(Alignment.TopCenter).padding(top = top + 16.dp, start = 12.dp, end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      SearchBar(state, actions)
      TopPanel(state, actions)
    }
    if (state.hasLocationAccess) {
      MyLocationButton(
          onClick = actions.onMyLocation,
          modifier =
              Modifier.align(Alignment.BottomEnd)
                  .padding(end = 16.dp, bottom = bottom + MY_LOCATION_BOTTOM),
      )
    }
    PlaceCard(
        state,
        actions.onUseLocation,
        Modifier.align(Alignment.BottomCenter)
            .padding(start = 12.dp, end = 12.dp, bottom = bottom + 16.dp),
    )
  }
}

@Composable
private fun SearchBar(state: LocationPickerUiState, actions: LocationPickerActions) {
  val shape = RoundedCornerShape(26.dp)
  val searching = state.search == PlaceSearchState.Searching
  Row(
      Modifier.fillMaxWidth()
          .height(52.dp)
          .figmaShadow(shape, offsetY = 2.dp, blur = 6.dp, alpha = 0.12f)
          .background(Bg, shape),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    // A 48 dp touch target around Figma's 20 dp chevron, 16 dp from the bar's edge.
    Box(
        Modifier.padding(start = 4.dp)
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = actions.onBack)
            .testTag(LocationPickerTags.BACK),
        contentAlignment = Alignment.Center,
    ) {
      Image(
          painterResource(R.drawable.ic_back),
          contentDescription = stringResource(R.string.location_picker_back),
          modifier = Modifier.size(20.dp),
      )
    }
    Box(Modifier.weight(1f).padding(start = 2.dp)) {
      if (state.query.isEmpty()) {
        Text(
            R.string.location_picker_search_placeholder,
            figmaText(15, FontWeight.Normal, 20.sp),
            Ink3,
        )
      }
      BasicTextField(
          value = state.query,
          onValueChange = actions.onQueryChange,
          singleLine = true,
          textStyle = figmaText(15, FontWeight.Normal, 20.sp).copy(color = Ink),
          cursorBrush = SolidColor(Ink),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions = KeyboardActions(onSearch = { actions.onSearch() }),
          modifier = Modifier.fillMaxWidth().testTag(LocationPickerTags.QUERY),
      )
    }
    Box(
        Modifier.padding(start = 8.dp, end = 4.dp)
            .size(width = 72.5.dp, height = 40.dp)
            .alpha(if (searching) 0.4f else 1f)
            .clip(RoundedCornerShape(20.dp))
            .background(Ink)
            .clickable(enabled = !searching, role = Role.Button, onClick = actions.onSearch)
            .testTag(LocationPickerTags.SEARCH),
        contentAlignment = Alignment.Center,
    ) {
      Text(R.string.location_picker_search, figmaText(13, FontWeight.Bold), Bg)
    }
  }
}

/** Below the search bar: the results or a message, else location access off, else the hint. */
@Composable
private fun TopPanel(state: LocationPickerUiState, actions: LocationPickerActions) {
  when (val search = state.search) {
    PlaceSearchState.Searching ->
        Panel(Modifier.testTag(LocationPickerTags.SEARCHING)) {
          Row(
              Modifier.padding(14.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            SmallSpinner()
            Text(
                R.string.location_picker_searching,
                figmaText(14, FontWeight.SemiBold, 18.sp),
                Ink2,
            )
          }
        }
    is PlaceSearchState.Results -> Results(search.places, actions.onResultSelect)
    PlaceSearchState.NoResults ->
        Banner(
            R.drawable.ic_picker_search,
            stringResource(R.string.location_picker_no_results_title),
            stringResource(R.string.location_picker_no_results_body, state.query.trim()),
        )
    is PlaceSearchState.Failed ->
        Banner(
            R.drawable.ic_alert,
            stringResource(
                if (search.reason == LookupFailure.OFFLINE) R.string.location_picker_offline_title
                else R.string.location_picker_search_failed_title
            ),
            stringResource(
                if (search.reason == LookupFailure.OFFLINE) R.string.location_picker_offline_body
                else R.string.location_picker_search_failed_body
            ),
            action = R.string.location_picker_try_again to actions.onSearch,
        )
    PlaceSearchState.Idle ->
        if (!state.hasLocationAccess) {
          Banner(
              R.drawable.ic_location_access_off,
              stringResource(R.string.location_picker_access_off_title),
              stringResource(R.string.location_picker_access_off_body),
              action = R.string.location_picker_allow_in_settings to actions.onAllowInSettings,
          )
        } else {
          Box(
              Modifier.clip(RoundedCornerShape(12.dp))
                  .background(Ink)
                  .padding(horizontal = 12.dp, vertical = 5.5.dp)
                  .testTag(LocationPickerTags.HINT)
          ) {
            Text(R.string.location_picker_hint, figmaText(12, FontWeight.SemiBold), Bg)
          }
        }
  }
}

@Composable
private fun Results(places: List<Place>, onSelect: (Place) -> Unit) {
  Panel(Modifier.testTag(LocationPickerTags.RESULTS)) {
    Column(Modifier.padding(top = 6.dp, bottom = 12.dp)) {
      places.forEachIndexed { index, place ->
        if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(DIVIDER))
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button) { onSelect(place) }
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag(LocationPickerTags.result(index)),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          PinTile(size = 36.dp, corner = 10.dp)
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TextValue(place.name, figmaText(14, FontWeight.Bold, 18.sp), Ink)
            if (place.address.isNotBlank()) {
              TextValue(place.address, figmaText(13, FontWeight.Normal, 18.sp), Ink2)
            }
          }
        }
      }
      Spacer(Modifier.height(8.dp))
      Text(
          R.string.location_picker_osm_credit,
          figmaText(12, FontWeight.Normal, 18.sp),
          Ink3,
          Modifier.fillMaxWidth(),
          TextAlign.Center,
      )
    }
  }
}

/** Figma's message panel: an icon, a bold title, a body and an optional red action. */
@Composable
private fun Banner(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    action: Pair<Int, () -> Unit>? = null,
) {
  Panel(Modifier.testTag(LocationPickerTags.BANNER)) {
    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextValue(title, figmaText(14, FontWeight.Bold, 18.sp), Ink)
        TextValue(body, figmaText(13, FontWeight.Normal, 18.sp), Ink2)
        if (action != null) {
          Text(
              action.first,
              figmaText(13, FontWeight.Bold, 18.sp),
              AccentText,
              Modifier.clickable(role = Role.Button, onClick = action.second)
                  .testTag(LocationPickerTags.BANNER_ACTION),
          )
        }
      }
    }
  }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  val shape = RoundedCornerShape(16.dp)
  Box(
      modifier
          .fillMaxWidth()
          .figmaShadow(shape, offsetY = 2.dp, blur = 12.dp, alpha = 0.12f)
          .background(Bg, shape)
          .clip(shape)
  ) {
    content()
  }
}

@Composable
private fun SmallSpinner() {
  val angle by
      rememberInfiniteTransition(label = "Search spinner")
          .animateFloat(
              initialValue = 0f,
              targetValue = 360f,
              animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
              label = "Search spinner angle",
          )
  Image(
      painterResource(R.drawable.ic_spinner_small),
      contentDescription = null,
      modifier = Modifier.size(18.dp).rotate(angle),
  )
}

@Composable
private fun MyLocationButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
  Box(
      modifier
          .size(48.dp)
          .figmaShadow(CircleShape, offsetY = 2.dp, blur = 5.dp, alpha = 0.15f)
          .background(Bg, CircleShape)
          .clip(CircleShape)
          .clickable(role = Role.Button, onClick = onClick)
          .testTag(LocationPickerTags.MY_LOCATION),
      contentAlignment = Alignment.Center,
  ) {
    Image(
        painterResource(R.drawable.ic_my_location),
        contentDescription = stringResource(R.string.location_picker_my_location),
        modifier = Modifier.size(22.dp),
    )
  }
}

/** What the card says about the pin, from the Figma frames. */
private data class CardText(
    val title: String,
    val body: String?,
    val pending: Boolean,
    val credit: Boolean,
)

@Composable
private fun cardText(pinPlace: PinPlaceState): CardText =
    when (pinPlace) {
      is PinPlaceState.Found ->
          CardText(pinPlace.place.name, pinPlace.place.address.ifBlank { null }, false, true)
      PinPlaceState.Resolving ->
          CardText(stringResource(R.string.location_picker_finding_address), null, true, true)
      PinPlaceState.YourLocation ->
          CardText(
              stringResource(R.string.location_picker_your_location),
              stringResource(R.string.location_picker_your_location_body),
              false,
              true,
          )
      is PinPlaceState.Unnamed ->
          when (pinPlace.failure) {
            null ->
                CardText(
                    stringResource(R.string.location_picker_dropped_pin),
                    stringResource(R.string.location_picker_no_address),
                    false,
                    true,
                )
            LookupFailure.OFFLINE ->
                CardText(
                    stringResource(R.string.location_picker_dropped_pin),
                    stringResource(R.string.location_picker_no_address_offline),
                    false,
                    false,
                )
            LookupFailure.UNEXPECTED ->
                CardText(
                    stringResource(R.string.location_picker_dropped_pin),
                    stringResource(R.string.location_picker_address_failed),
                    false,
                    false,
                )
          }
    }

@Composable
private fun PlaceCard(state: LocationPickerUiState, onUse: () -> Unit, modifier: Modifier) {
  val shape = RoundedCornerShape(24.dp)
  val text = cardText(state.pinPlace)
  Column(
      modifier
          .fillMaxWidth()
          .figmaShadow(shape, offsetY = 8.dp, blur = 14.dp, alpha = 0.18f)
          .background(Bg, shape)
          .padding(start = 16.dp, end = 16.dp, top = 16.dp)
          .testTag(LocationPickerTags.CARD)
  ) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      PinTile(size = 44.dp, corner = 12.dp)
      Column {
        TextValue(
            text.title,
            figmaText(16, FontWeight.Bold),
            if (text.pending) Ink3 else Ink,
        )
        if (text.body != null) TextValue(text.body, figmaText(13, FontWeight.Normal), Ink2)
      }
    }
    Spacer(Modifier.height(12.dp))
    Box(
        Modifier.fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Ink)
            .clickable(role = Role.Button, onClick = onUse)
            .testTag(LocationPickerTags.USE),
        contentAlignment = Alignment.Center,
    ) {
      Text(R.string.location_picker_use, figmaText(15, FontWeight.Bold), Bg)
    }
    if (text.credit) {
      Spacer(Modifier.height(11.5.dp))
      Text(
          R.string.location_picker_address_credit,
          figmaText(12, FontWeight.Normal),
          Ink3,
          Modifier.fillMaxWidth(),
          TextAlign.Center,
      )
      Spacer(Modifier.height(16.dp))
    } else {
      Spacer(Modifier.height(19.dp))
    }
  }
}

/** The pin on a light red tile, as in the results and the place card. */
@Composable
private fun PinTile(size: Dp, corner: Dp) {
  Box(
      Modifier.size(size).clip(RoundedCornerShape(corner)).background(AccentSoft),
      contentAlignment = Alignment.Center,
  ) {
    Image(
        painterResource(R.drawable.ic_pin),
        contentDescription = null,
        modifier = Modifier.size(20.dp),
    )
  }
}

@Composable
private fun Text(
    @StringRes text: Int,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
) = TextValue(stringResource(text), style, color, modifier, align)

@Composable
private fun TextValue(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
) =
    androidx.compose.material3.Text(
        text,
        style = style.copy(color = color, textAlign = align ?: TextAlign.Unspecified),
        modifier = modifier,
    )

/** Figma's text: Plus Jakarta Sans, with the font's own line height unless Figma fixes one. */
private fun figmaText(size: Int, weight: FontWeight, lineHeight: TextUnit = TextUnit.Unspecified) =
    TextStyle(
        fontFamily = PlusJakartaSans,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight,
    )

/** Figma's drop shadow: Ink at [alpha], [offsetY] down, blurred by [blur]. */
private fun Modifier.figmaShadow(shape: Shape, offsetY: Dp, blur: Dp, alpha: Float) =
    dropShadow(
        shape,
        Shadow(radius = blur, color = Ink.copy(alpha = alpha), offset = DpOffset(0.dp, offsetY)),
    )

/** The divider between search results. */
private val DIVIDER = Color(0xFFE8E9E5)

/** The location button's bottom edge: 232 dp above the bottom of the 800 dp Figma frame. */
private val MY_LOCATION_BOTTOM = 232.dp

/** Keeps the map's logo and attribution just above the place card (167 dp + 16 dp + 8 dp). */
private val MAP_CREDITS_BOTTOM = 191.dp
