// Import statements
package com.programminghut.realtime_object

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.programminghut.realtime_object.ml.SsdMobilenetV11Metadata1
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.*

// Main activity class
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // TextToSpeech instance
    private var tts: TextToSpeech? = null

    // List of labels for object detection
    lateinit var labels: List<String>

    // Colors for bounding boxes
    var colors = listOf<Int>(
        Color.BLUE, Color.GREEN, Color.RED, Color.CYAN, Color.GRAY, Color.BLACK,
        Color.DKGRAY, Color.MAGENTA, Color.YELLOW, Color.RED
    )

    // Paint object for drawing on canvas
    val paint = Paint()

    // Image processing components
    lateinit var imageProcessor: ImageProcessor
    lateinit var bitmap: Bitmap

    // Views and camera components
    lateinit var imageView: ImageView
    lateinit var cameraDevice: CameraDevice
    lateinit var handler: Handler
    lateinit var cameraManager: CameraManager
    lateinit var textureView: TextureView
    lateinit var model: SsdMobilenetV11Metadata1

    // Flag to track TextToSpeech initialization
    private var ttsInitialized = false

    // Called when the activity is created
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Request camera permission
        get_permission()

        // Load labels from a file
        labels = FileUtil.loadLabels(this, "labels.txt")

        // Image processing setup
        imageProcessor = ImageProcessor.Builder().add(ResizeOp(300, 300, ResizeOp.ResizeMethod.BILINEAR)).build()

        // Create an instance of the object detection model
        model = SsdMobilenetV11Metadata1.newInstance(this)

        // Initialize a handler thread for camera operations
        val handlerThread = HandlerThread("videoThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        // Initialize TextToSpeech
        tts = TextToSpeech(this, this)

        // Initialize views and camera components
        imageView = findViewById(R.id.imageView)
        textureView = findViewById(R.id.textureView)

        // Set up the SurfaceTextureListener for the TextureView
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            // Called when the SurfaceTexture is available
            override fun onSurfaceTextureAvailable(p0: SurfaceTexture, p1: Int, p2: Int) {
                open_camera()
            }

            // Called when the size of the SurfaceTexture changes
            override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture, p1: Int, p2: Int) {
            }

            // Called when the SurfaceTexture is destroyed
            override fun onSurfaceTextureDestroyed(p0: SurfaceTexture): Boolean {
                return false
            }

            // Called when the SurfaceTexture is updated
            override fun onSurfaceTextureUpdated(p0: SurfaceTexture) {
                // Update the textureView with the processed image and bounding boxes
                updateTextureView()
            }
        }

        // Get the camera service
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    // Called when the activity is destroyed
    override fun onDestroy() {
        super.onDestroy()

        // Close the object detection model
        model.close()

        // Shutdown TextToSpeech when the activity is destroyed
        if (tts != null) {
            tts!!.stop()
            tts!!.shutdown()
        }
    }

    // Open the camera with appropriate permissions
    @SuppressLint("MissingPermission")
    fun open_camera() {
        cameraManager.openCamera(cameraManager.cameraIdList[0], object : CameraDevice.StateCallback() {
            override fun onOpened(p0: CameraDevice) {
                cameraDevice = p0

                var surfaceTexture = textureView.surfaceTexture
                var surface = Surface(surfaceTexture)

                var captureRequest =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                captureRequest.addTarget(surface)

                cameraDevice.createCaptureSession(
                    listOf(surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(p0: CameraCaptureSession) {
                            p0.setRepeatingRequest(captureRequest.build(), null, null)
                        }

                        override fun onConfigureFailed(p0: CameraCaptureSession) {
                        }
                    }, handler
                )
            }

            override fun onDisconnected(p0: CameraDevice) {
            }

            override fun onError(p0: CameraDevice, p1: Int) {
            }
        }, handler)
    }

    // Request camera permission
    fun get_permission() {
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 101)
        }
    }

    // Called when permission is granted or denied
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            get_permission()
        }
    }

    // Called when TextToSpeech initialization is complete
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts!!.setLanguage(Locale.US)

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "The Language not supported!")
            } else {
                ttsInitialized = true // Set the flag to true when initialization is complete
                // Call speakOut() when TextToSpeech is successfully initialized
                speakOut()
            }
        } else {
            Log.e("TTS", "Initialization failed with status: $status")
        }
    }

    // Variable to store the last spoken label
    private var lastSpokenLabel: String? = null

    // Speak out the object label using TextToSpeech
    private fun speakOut() {
        // Get the object labels and speak them out
        val text = getLabelsText()

        // Check if the label is different from the last spoken label
        if (text.isNotEmpty() && text != lastSpokenLabel) {
            // Speak only if the label is not empty and different from the last spoken label
            tts!!.speak(text, TextToSpeech.QUEUE_ADD, null, "")

            // Update the last spoken label
            lastSpokenLabel = text
        }
    }

    // Get the object label with the maximum area
    private fun getLabelsText(): String {
        var labelsText = ""

        // Get the dimensions of the bitmap
        val h = bitmap.height
        val w = bitmap.width

        // Process the image using the model
        var image = TensorImage.fromBitmap(bitmap)
        image = imageProcessor.process(image)

        // Get model outputs
        val outputs = model.process(image)
        val locations = outputs.locationsAsTensorBuffer.floatArray
        val classes = outputs.classesAsTensorBuffer.floatArray
        val scores = outputs.scoresAsTensorBuffer.floatArray

        var maxArea = 0.0
        var maxAreaLabel = ""

        // Iterate through the detected objects
        for (index in 0 until scores.size) {
            val x = index * 4
            if (scores[index] > 0.5) {
                // Calculate the area of the detected object
                val area = Math.abs(locations[x + 2] - locations[x]) * Math.abs(locations[x + 3] - locations[x + 1])

                // Check if the current area is greater than the maximum area
                if (area > maxArea) {
                    // Update the maximum area and corresponding label
                    maxArea = area.toDouble()
                    maxAreaLabel = labels[classes[index].toInt()]
                }
            }
        }

        // Trim the label and set it as the result
        labelsText = maxAreaLabel.trim()
        return labelsText
    }

    // Update the TextureView with the processed image and bounding boxes
    private fun updateTextureView() {
        bitmap = textureView.bitmap!!
        var image = TensorImage.fromBitmap(bitmap)
        image = imageProcessor.process(image)

        val outputs = model.process(image)
        val locations = outputs.locationsAsTensorBuffer.floatArray
        val classes = outputs.classesAsTensorBuffer.floatArray
        val scores = outputs.scoresAsTensorBuffer.floatArray

        var mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)

        val h = mutable.height
        val w = mutable.width
        paint.textSize = h / 15f
        paint.strokeWidth = h / 85f
        var x = 0
        scores.forEachIndexed { index, fl ->
            x = index
            x *= 4
            if (fl > 0.5) {
                paint.setColor(colors.get(index))
                paint.style = Paint.Style.STROKE
                canvas.drawRect(
                    RectF(
                        locations.get(x + 1) * w,
                        locations.get(x) * h,
                        locations.get(x + 3) * w,
                        locations.get(x + 2) * h
                    ), paint
                )
                paint.style = Paint.Style.FILL
                canvas.drawText(
                    labels.get(classes.get(index).toInt()),
                    locations.get(x + 1) * w,
                    locations.get(x) * h,
                    paint
                )
            }
        }

        imageView.setImageBitmap(mutable)

        if (ttsInitialized) {
            // Call speakOut() when TextToSpeech is successfully initialized
            speakOut()
        }
    }
}
