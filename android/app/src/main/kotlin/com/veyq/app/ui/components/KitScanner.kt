package com.veyq.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.veyq.app.ui.theme.VeyqTokens
import com.veyq.app.ui.theme.veyqColors
import com.veyq.client.KitData
import com.veyq.client.parseKit
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage

/**
 * A full-screen live QR scanner over **CameraX + bundled ML Kit** — the Android twin of Apple's
 * `KitScanner`. Decodes a `veyqkit:` QR and calls [onResult] with the parsed kit; non-kit QR codes are
 * ignored. Requests the `CAMERA` permission on demand; denied / no-camera surface a message and let the
 * user fall back to upload/paste. Operator-blind: the decode is on-device; only the parsed kit leaves here.
 */
@Composable
fun KitScanner(onResult: (KitData) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val c = veyqColors

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var denied by remember { mutableStateOf(false) }
    var delivered by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(modifier = Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Scan your Emergency Kit", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = c.text)

            when {
                granted -> {
                    CameraPreview(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(VeyqTokens.radius)),
                        onQr = { text ->
                            if (!delivered) {
                                val kit = parseKit(text)
                                if (kit != null) {
                                    delivered = true
                                    onResult(kit)
                                }
                            }
                        },
                    )
                    Text("Point the camera at the QR code on your kit.", fontSize = 13.sp, color = c.muted)
                }
                denied -> MessageBox("Camera access is off. Allow it in settings, or upload the kit instead.")
                else -> Text("Requesting camera access…", fontSize = 13.sp, color = c.muted)
            }

            Text(
                "Cancel",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = c.accent,
                modifier = Modifier.testTag("Cancel").clickable(onClick = onCancel),
            )
        }
    }
}

/** The CameraX preview + an ML Kit QR analyzer, bound to the composition's lifecycle. */
@Composable
private fun CameraPreview(modifier: Modifier, onQr: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val scanner = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
            )
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy -> analyze(scanner, proxy, onQr) }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

/** Run one frame through ML Kit; report the first QR's raw value. Always closes the proxy. */
@OptIn(ExperimentalGetImage::class)
private fun analyze(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    proxy: ImageProxy,
    onQr: (String) -> Unit,
) {
    val image = proxy.image
    if (image == null) {
        proxy.close()
        return
    }
    val input = InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees)
    scanner.process(input)
        .addOnSuccessListener { barcodes -> barcodes.firstOrNull()?.rawValue?.let(onQr) }
        .addOnCompleteListener { proxy.close() }
}
