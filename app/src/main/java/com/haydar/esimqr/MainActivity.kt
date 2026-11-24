package com.haydar.esimqr

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.euicc.DownloadableSubscription
import android.telephony.euicc.EuiccManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_DOWNLOAD = "com.haydar.esimqr.DOWNLOAD_RESULT"
        private const val REQ_RESOLVE = 1001
    }

    private lateinit var euicc: EuiccManager
    private lateinit var status: TextView
    private lateinit var codeField: EditText
    private val analyzerExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var scanned = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startCamera()
            else status.text = "Нет доступа к камере — вставьте код вручную"
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (resultCode) {
                EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK -> {
                    status.text = "eSIM успешно добавлена"
                }

                EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_RESOLVABLE_ERROR -> {
                    try {
                        val callback = PendingIntent.getBroadcast(
                            this@MainActivity,
                            0,
                            Intent(ACTION_DOWNLOAD).setPackage(packageName),
                            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                        euicc.startResolutionActivity(
                            this@MainActivity,
                            REQ_RESOLVE,
                            intent,
                            callback
                        )
                    } catch (e: Exception) {
                        status.text = "Не удалось открыть подтверждение: ${e.message}"
                        scanned = false
                    }
                }

                else -> {
                    val detail = intent.getIntExtra(
                        EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE,
                        -1
                    )
                    status.text = "Ошибка установки (код $resultCode, детали $detail)"
                    scanned = false
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        codeField = findViewById(R.id.code)
        euicc = getSystemService(Context.EUICC_SERVICE) as EuiccManager

        if (!euicc.isEnabled) {
            status.text = "Это устройство не поддерживает eSIM"
        }

        val filter = IntentFilter(ACTION_DOWNLOAD)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        findViewById<Button>(R.id.install).setOnClickListener {
            install(codeField.text.toString())
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        analyzerExecutor.shutdown()
        super.onDestroy()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.preview).surfaceProvider)
            }

            val scanner = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
            )

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(analyzerExecutor) { proxy ->
                val media = proxy.image
                if (media == null || scanned) {
                    proxy.close()
                    return@setAnalyzer
                }

                val image = InputImage.fromMediaImage(
                    media,
                    proxy.imageInfo.rotationDegrees
                )

                scanner.process(image)
                    .addOnSuccessListener { list ->
                        val raw = list.firstOrNull {
                            it.rawValue?.contains("$") == true
                        }?.rawValue

                        if (raw != null && !scanned) {
                            scanned = true
                            runOnUiThread {
                                codeField.setText(raw)
                                install(raw)
                            }
                        }
                    }
                    .addOnCompleteListener { proxy.close() }
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun install(raw: String) {
        val code = raw.trim()
            .removePrefix("LPA:")
            .removePrefix("lpa:")

        if (!code.startsWith("1$") || code.split('$').size < 3) {
            Toast.makeText(this, "Неверный формат кода eSIM", Toast.LENGTH_LONG).show()
            scanned = false
            return
        }

        if (!euicc.isEnabled) {
            status.text = "eSIM не поддерживается на этом устройстве"
            scanned = false
            return
        }

        status.text = "Установка…"
        val subscription = DownloadableSubscription.forActivationCode(code)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_DOWNLOAD).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        euicc.downloadSubscription(subscription, true, pendingIntent)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_RESOLVE && resultCode != Activity.RESULT_OK) {
            scanned = false
        }
    }
}
