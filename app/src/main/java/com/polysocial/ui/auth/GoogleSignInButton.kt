// Contributors: Google (official pre-approved Android sign-in artwork); OpenAI Codex
// (GPT-6.1 Sol, medium; shared Compose wrapper preserving the artwork's proportions;
// removed the rectangular press indication from the official PNG).
package com.polysocial.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.polysocial.R

/**
 * Uses Google's unchanged light/pill Android PNG at 4x (720 × 160), including its official font and
 * multicolor logo. Source: https://developers.google.com/identity/branding-guidelines
 * (signin-assets.zip, Android + Web/PNG @4x/Light, Show text=Yes, Shape=Pill).
 */
@Composable
fun GoogleSignInButton(tag: String, onClick: () -> Unit, enabled: Boolean = true) {
  Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Image(
        painter = painterResource(R.drawable.google_sign_in_light),
        contentDescription = stringResource(R.string.google_sign_in),
        modifier =
            Modifier.height(52.dp)
                .aspectRatio(4.5f)
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .testTag(tag),
    )
  }
}
