// Contributors: Claude (wrote this test).
package com.polysocial.model.event

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.polysocial.model.auth.FakeAuthRepository
import com.polysocial.model.network.NetworkMonitor
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class EventModuleTest {

  @Test
  fun theApp_createsEventsInFirestore() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    if (FirebaseApp.getApps(context).isEmpty()) {
      FirebaseApp.initializeApp(
          context,
          FirebaseOptions.Builder()
              .setApplicationId("1:0:android:0")
              .setProjectId("polysocial-test")
              .setApiKey("test")
              .build(),
      )
    }
    val offline =
        object : NetworkMonitor {
          override fun isOnline() = false
        }

    val repository = EventModule.eventRepository(FakeAuthRepository(), offline)

    assertTrue(repository is FirestoreEventRepository)
  }
}
