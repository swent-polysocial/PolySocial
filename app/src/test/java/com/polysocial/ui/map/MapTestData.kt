// Contributors: OpenAI Codex (fixed map test clock, #50).
package com.polysocial.ui.map

import com.polysocial.model.event.TEST_NOW
import java.time.Clock

internal val MAP_TEST_CLOCK: Clock = Clock.fixed(TEST_NOW, MAP_TIME_ZONE)
