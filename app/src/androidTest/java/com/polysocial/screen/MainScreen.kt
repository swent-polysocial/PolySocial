// Contributors: Claude (app shell nodes instead of the template greeting, #41).
package com.polysocial.screen

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import com.polysocial.resources.C
import io.github.kakaocup.compose.node.element.ComposeScreen
import io.github.kakaocup.compose.node.element.KNode

class MainScreen(semanticsProvider: SemanticsNodeInteractionsProvider) :
    ComposeScreen<MainScreen>(
        semanticsProvider = semanticsProvider,
        viewBuilderAction = { hasTestTag(C.Tag.app_shell) },
    ) {

  val appBarTitle: KNode = child { hasTestTag(C.Tag.app_bar_title) }
  val bottomNav: KNode = child { hasTestTag(C.Tag.bottom_nav) }
  val eventsScreen: KNode = child { hasTestTag(C.Tag.screen_events) }
}
