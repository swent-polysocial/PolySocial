// Contributors: Claude (wrote this test, #45).
package com.polysocial.model.firebase

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class FirebaseModuleTest {

  @Test
  fun theApp_usesTheDefaultFirestoreInstance() {
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

    assertSame(FirebaseFirestore.getInstance(), FirebaseModule.firestore())
  }
}
