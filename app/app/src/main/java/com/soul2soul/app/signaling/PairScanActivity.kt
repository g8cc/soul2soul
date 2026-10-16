package com.soul2soul.app.signaling

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.soul2soul.app.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 扫一扫配对：只认自家 soul2soul://pair 载荷（或裸 6 位码），陌生码继续扫。
 * 回传 EXTRA_TEXT = 解析出的 6 位配对码，主页沿用 pair.enter 老通道。
 */
class PairScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TEXT = "scanned_text"
        private const val REQUEST_CAMERA = 101
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val finished = AtomicBoolean(false)
    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    setResult(RESULT_CANCELED)
                    finish()
                }
            },
        )
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            finish()
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            val provider = runCatching { future.get() }.getOrNull()
            if (provider == null) {
                Toast.makeText(this, R.string.scan_failed, Toast.LENGTH_SHORT).show()
                finish()
                return@addListener
            }
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(
                    findViewById<androidx.camera.view.PreviewView>(R.id.previewView).surfaceProvider
                )
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor) { proxy -> analyze(proxy) } }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            }.onFailure {
                Toast.makeText(this, R.string.scan_failed, Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        if (finished.get()) {
            proxy.close()
            return
        }
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { codes ->
                for (c in codes) {
                    val code = PairQr.parse(c.rawValue)
                    if (code != null && finished.compareAndSet(false, true)) {
                        findViewById<android.view.View>(R.id.tvScanHint)
                            .performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, code))
                        finish()
                        break
                    }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    override fun onDestroy() {
        runCatching { scanner.close() }
        executor.shutdown()
        super.onDestroy()
    }
}
