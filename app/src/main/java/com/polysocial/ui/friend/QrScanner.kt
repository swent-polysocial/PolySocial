// Contributors: Claude Opus 5.5 (drafted the camera QR scanner behind an interface, #359);
// Konstantinos Perdikeas (directed, reviewed and owns it).
package com.polysocial.ui.friend

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * The camera, behind an interface so screens and tests never touch it (CI's emulators have no
 * camera): it shows the live picture inside the given modifier and reports the text of each QR code
 * it reads. Tests pass a fake that reports codes by hand.
 */
interface QrScanner {
  /**
   * Shows the camera and calls [onCode] on the main thread with each QR code's raw text, while
   * [scanning]; with the torch on while [torchOn]. The camera runs only while this is shown and the
   * screen is in the foreground.
   */
  @Composable
  fun Camera(scanning: Boolean, torchOn: Boolean, onCode: (String) -> Unit, modifier: Modifier)
}

/**
 * The real [QrScanner]: CameraX's preview and frames, read by ML Kit's bundled barcode model on the
 * device. Frames are analysed one at a time off the main thread and dropped right after; none is
 * stored, uploaded or logged, and neither is a code's text.
 */
object CameraQrScanner : QrScanner {
  @Composable
  override fun Camera(
      scanning: Boolean,
      torchOn: Boolean,
      onCode: (String) -> Unit,
      modifier: Modifier,
  ) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isScanning by rememberUpdatedState(scanning)
    val report by rememberUpdatedState(onCode)
    val controller = remember { LifecycleCameraController(context) }
    DisposableEffect(lifecycleOwner) {
      val reader =
          BarcodeScanning.getClient(
              BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
          )
      val frames = Executors.newSingleThreadExecutor()
      controller.setImageAnalysisAnalyzer(frames) { frame ->
        readFrame(frame, reader, isScanning) { code -> report(code) }
      }
      controller.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
      controller.bindToLifecycle(lifecycleOwner)
      onDispose {
        controller.unbind()
        controller.clearImageAnalysisAnalyzer()
        reader.close()
        frames.shutdown()
      }
    }
    LaunchedEffect(torchOn) { controller.enableTorch(torchOn) }
    AndroidView(
        factory = { PreviewView(it).apply { this.controller = controller } },
        modifier = modifier,
    )
  }

  /** Reads one frame's QR codes, then frees it; a failed read just waits for the next frame. */
  @OptIn(ExperimentalGetImage::class)
  private fun readFrame(
      frame: ImageProxy,
      reader: com.google.mlkit.vision.barcode.BarcodeScanner,
      scanning: Boolean,
      onCode: (String) -> Unit,
  ) {
    val image = frame.image
    if (!scanning || image == null) {
      frame.close()
      return
    }
    reader
        .process(InputImage.fromMediaImage(image, frame.imageInfo.rotationDegrees))
        .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { it.rawValue }?.let(onCode) }
        .addOnCompleteListener { frame.close() }
  }
}
