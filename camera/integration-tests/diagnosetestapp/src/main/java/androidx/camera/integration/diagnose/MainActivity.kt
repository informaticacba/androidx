/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.camera.integration.diagnose

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.CameraController.IMAGE_CAPTURE
import androidx.camera.view.CameraController.VIDEO_CAPTURE
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.ExperimentalVideo
import androidx.camera.view.video.OnVideoSavedCallback
import androidx.camera.view.video.OutputFileOptions
import androidx.camera.view.video.OutputFileResults
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController.IMAGE_ANALYSIS
import androidx.core.util.Preconditions
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var cameraController: LifecycleCameraController
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var executor: Executor
    private lateinit var tabLayout: TabLayout
    private lateinit var diagnosis: Diagnosis
    private lateinit var barcodeScanner: BarcodeScanner
    private lateinit var analyzer: MlKitAnalyzer
    private lateinit var diagnoseBtn: Button
    private lateinit var calibrationExecutor: ExecutorService
    private var calibrationThreadId: Long = -1
    private lateinit var diagnosisDispatcher: ExecutorCoroutineDispatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview_view)
        overlayView = findViewById(R.id.overlay_view)
        overlayView.visibility = View.INVISIBLE
        cameraController = LifecycleCameraController(this)
        previewView.controller = cameraController
        executor = ContextCompat.getMainExecutor(this)
        tabLayout = findViewById(R.id.tabLayout_view)
        diagnosis = Diagnosis()
        barcodeScanner = BarcodeScanning.getClient()
        diagnoseBtn = findViewById(R.id.diagnose_btn)
        calibrationExecutor = Executors.newSingleThreadExecutor() { runnable ->
            val thread = Executors.defaultThreadFactory().newThread(runnable)
            thread.name = "CalibrationThread"
            calibrationThreadId = thread.id
            return@newSingleThreadExecutor thread
        }
        diagnosisDispatcher = Executors.newFixedThreadPool(1).asCoroutineDispatcher()

        // Request CAMERA permission and fail gracefully if not granted.
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Setting up Tabs
        val photoTab = tabLayout.newTab().setText("Photo")
        photoTab.view.id = R.id.image_capture
        tabLayout.addTab(photoTab)
        val videoTab = tabLayout.newTab().setText("Video")
        videoTab.view.id = R.id.video_capture
        tabLayout.addTab(videoTab)
        val diagnoseTab = tabLayout.newTab().setText("Diagnose")
        diagnoseTab.view.id = R.id.diagnose
        tabLayout.addTab(diagnoseTab)

        // Setup UI events
        // TODO: switch TabItems to TabLayout.Tab for selecting on id
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                Log.d(TAG, "tab selected id:${tab?.view?.id}")
                selectMode(tab?.view?.id)
            }

            override fun onTabReselected(tab: TabLayout.Tab?) {
                Log.d(TAG, "tab reselected:${tab?.view?.id}")
                selectMode(tab?.view?.id)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {
                Log.d(TAG, "tab unselected:${tab?.view?.id}")
                if (tab?.view?.id == R.id.diagnose) {
                    // disable overlay
                    overlayView.visibility = View.INVISIBLE
                    // unbind MLKit analyzer
                    cameraController.clearImageAnalysisAnalyzer()
                }
            }
        })

        diagnoseBtn.setOnClickListener {
            lifecycleScope.launch {
                try {
                    val tempFile = withContext(diagnosisDispatcher) {
                        Log.i(TAG, "dispatcher: ${Thread.currentThread().name}")
                        diagnosis.collectDeviceInfo(baseContext)
                    }
                    Log.d(TAG, "file at ${tempFile.path}")
                    val msg = "Successfully collected device info"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                } catch (e: IOException) {
                    val msg = "Failed to collect information"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.e(TAG, "IOException caught: ${e.message}")
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                // TODO: fail gracefully
                finish()
            }
        }
    }

    private fun startCamera() {
        // Setup CameraX
        cameraController.bindToLifecycle(this)
        Log.d(TAG, "started camera")
    }

    private fun selectMode(id: Int?) {
        when (id) {
            R.id.image_capture -> takePhoto()
            R.id.video_capture -> captureVideo()
            R.id.diagnose -> diagnose()
        }
    }

    private fun takePhoto() {
        cameraController.setEnabledUseCases(IMAGE_CAPTURE)

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            .build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        cameraController.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    val msg = "Photo capture failed: ${exc.message}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            }
        )
    }

    @SuppressLint("NullAnnotationGroup")
    @OptIn(ExperimentalVideo::class)
    private fun captureVideo() {
        // determine whether the onclick is to start recording or stop recording
        if (cameraController.isRecording) {
            cameraController.stopRecording()
            val msg = "video stopped recording"
            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
            Log.d(TAG, msg)
        } else {
            // enabling video capture
            cameraController.setEnabledUseCases(VIDEO_CAPTURE)

            // building file output
            val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis())
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
                }
            }
            val outputFileOptions = OutputFileOptions
                .builder(
                    contentResolver,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )
                .build()
            Log.d(TAG, "finished composing video name")

            // start recording
            try {
                cameraController.startRecording(
                    outputFileOptions,
                    executor,
                    object : OnVideoSavedCallback {
                        override fun onVideoSaved(outputFileResults: OutputFileResults) {
                            val msg = "Video record succeeded: " + outputFileResults.savedUri
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                            Log.d(TAG, msg)
                        }

                        override fun onError(
                            videoCaptureError: Int,
                            message: String,
                            cause: Throwable?
                        ) {
                            Log.e(TAG, "Video saving failed: $message")
                        }
                    }
                )
                val msg = "video recording"
                Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                Log.d(TAG, msg)
            } catch (exception: RuntimeException) {
                Log.e(TAG, "Video failed to record: " + exception.message)
            }
        }
    }

    private fun diagnose() {
        // enable overlay and diagnose button
        overlayView.visibility = View.VISIBLE
        // enable image analysis use case
        cameraController.setEnabledUseCases(IMAGE_ANALYSIS)

        val calibrate = Calibration(
            Size(previewView.width, previewView.height))

        analyzer = MlKitAnalyzer(
            listOf(barcodeScanner),
            CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
            calibrationExecutor
        ) { result ->
            // validating thread
            checkCalibrationThread()
            val barcodes = result.getValue(barcodeScanner)
            if (barcodes != null && barcodes.size > 0) {
                calibrate.analyze(barcodes)
                // run UI on main thread
                lifecycleScope.launch {
                    // gives overlayView access to Calibration
                    overlayView.setCalibrationResult(calibrate)
                    // enable diagnose button when alignment is successful
                    diagnoseBtn.isEnabled = calibrate.isAligned
                    overlayView.invalidate()
                }
            }
        }
        cameraController.setImageAnalysisAnalyzer(
            calibrationExecutor, analyzer)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkCalibrationThread() {
        Preconditions.checkState(calibrationThreadId == Thread.currentThread().id,
            "Not working on Calibration Thread")
    }

    override fun onDestroy() {
        super.onDestroy()
        calibrationExecutor.shutdown()
        diagnosisDispatcher.close()
    }

    companion object {
        private const val TAG = "DiagnoseApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}