// Contributors: Claude Opus 5.5 (wrote these tests, #359); Konstantinos Perdikeas (directed,
// reviewed and owns them).
package com.polysocial.ui.friend

import android.media.Image
import android.os.Looper
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Every frame must be closed, or CameraX stops sending new ones; codes count only while scanning.
 */
@RunWith(AndroidJUnit4::class)
class ReadFrameTest {
  private val image = mockk<Image>()
  private val frame =
      mockk<ImageProxy>(relaxUnitFun = true) {
        every { this@mockk.image } returns this@ReadFrameTest.image
        every { imageInfo } returns mockk<ImageInfo> { every { rotationDegrees } returns 90 }
      }
  private val reader = mockk<BarcodeScanner>()
  private val reported = mutableListOf<String>()

  @Before
  fun mockInputImage() {
    mockkStatic(InputImage::class)
    every { InputImage.fromMediaImage(image, 90) } returns mockk()
  }

  @After fun unmock() = unmockkStatic(InputImage::class)

  private fun code(raw: String?) = mockk<Barcode> { every { rawValue } returns raw }

  private fun read(scanning: Boolean = true) {
    readFrame(frame, reader, scanning) { reported += it }
    shadowOf(Looper.getMainLooper()).idle()
  }

  @Test
  fun aReadCode_isReportedAndTheFrameClosed() {
    every { reader.process(any<InputImage>()) } returns
        Tasks.forResult(listOf(code(null), code("polysocial://friend/lea"), code("other")))

    read()

    assertEquals(listOf("polysocial://friend/lea"), reported)
    verify(exactly = 1) { frame.close() }
  }

  @Test
  fun aFrameWithoutACode_reportsNothingAndIsClosed() {
    every { reader.process(any<InputImage>()) } returns Tasks.forResult(emptyList())

    read()

    assertEquals(emptyList<String>(), reported)
    verify(exactly = 1) { frame.close() }
  }

  @Test
  fun aFailedRead_reportsNothingAndIsClosed() {
    every { reader.process(any<InputImage>()) } returns
        Tasks.forException(IllegalStateException("model failed"))

    read()

    assertEquals(emptyList<String>(), reported)
    verify(exactly = 1) { frame.close() }
  }

  @Test
  fun whileNotScanning_theFrameIsClosedUnread() {
    read(scanning = false)

    verify(exactly = 0) { reader.process(any<InputImage>()) }
    verify(exactly = 1) { frame.close() }
  }

  @Test
  fun aFrameWithoutAnImage_isClosedUnread() {
    every { frame.image } returns null

    read()

    verify(exactly = 0) { reader.process(any<InputImage>()) }
    verify(exactly = 1) { frame.close() }
  }
}
