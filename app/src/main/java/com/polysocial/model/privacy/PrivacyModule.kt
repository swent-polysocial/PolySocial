// Contributors: OpenAI Codex (Hilt privacy contact service and resource configuration, #103).
package com.polysocial.model.privacy

import android.content.Context
import com.polysocial.R
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier

/** The private project-team address, left empty until the team confirms it. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class PrivacyContactAddress

/** Keeps Android email access and resource configuration outside the privacy Composables. */
@Module
@InstallIn(SingletonComponent::class)
object PrivacyModule {
  @Provides
  @PrivacyContactAddress
  fun contactEmail(@ApplicationContext context: Context): String =
      context.getString(R.string.privacy_contact_email)

  @Provides
  fun contactLauncher(@ApplicationContext context: Context): PrivacyContactLauncher =
      AndroidPrivacyContactLauncher(context)
}
