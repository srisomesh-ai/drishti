package com.bharatgps.drishti

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var btnPause: Button

    private lateinit var speaker: Speaker
    private lateinit var guide: Guide
    private lateinit var store: FaceStore
    private lateinit var analysisExecutor: ExecutorService
    private val main = Handler(Looper.getMainLooper())

    // created on the analysis thread
    private var objectDetector: ObjectDetector? = null
    private var embedder: FaceEmbedder? = null
    private var faceDetector: FaceDetector? = null
    private var modelError: String? = null

    @Volatile private var paused = false

    // face tracking cache: trackingId -> (name, sim, lastEmbedTime, firstSeen)
    private data class Track(var name: String?, var lastEmbed: Long, val firstSeen: Long)
    private val tracks = HashMap<Int, Track>()

    // enrolment
    @Volatile private var enrolling = false
    private var enrollStart = 0L
    private var lastSample = 0L
    private val samples = mutableListOf<FloatArray>()

    private val MATCH_THRESHOLD = 0.70f
    private val SAMPLES_NEEDED = 6

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else speaker.say("Camera permission is needed. Please allow camera in settings.", true)
    }

    private val speechForName = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (res.resultCode == RESULT_OK && !text.isNullOrBlank()) {
            savePerson(cleanName(text))
        } else {
            speaker.say("I did not hear a name. You can type it, or press Add Person again.", true)
            typeNameDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        btnPause = findViewById(R.id.btnPause)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        speaker = Speaker(this)
        guide = Guide(speaker)
        store = FaceStore(this)
        analysisExecutor = Executors.newSingleThreadExecutor()

        findViewById<Button>(R.id.btnDescribe).setOnClickListener { describe() }
        findViewById<Button>(R.id.btnAddPerson).setOnClickListener { startEnroll() }
        findViewById<Button>(R.id.btnPeople).setOnClickListener { peopleDialog() }
        btnPause.setOnClickListener { togglePause() }

        // double tap anywhere on the camera = describe
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean { describe(); return true }
        })
        findViewById<android.view.View>(R.id.cameraArea).setOnTouchListener { v, ev ->
            gd.onTouchEvent(ev); if (ev.action == MotionEvent.ACTION_UP) v.performClick(); true
        }
        findViewById<android.view.View>(R.id.cameraArea).setOnClickListener { }

        speaker.say("Drishti started. Point the phone forward. Double tap the screen to hear everything in front of you.", true)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    // ------------------------------------------------------------------ camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { analyze(it) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun ensureModels() {
        if (objectDetector != null || modelError != null) return
        try {
            val opts = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setNumThreads(4).build())
                .setMaxResults(6)
                .setScoreThreshold(0.45f)
                .build()
            objectDetector = ObjectDetector.createFromFileAndOptions(this, "efficientdet.tflite", opts)
            embedder = FaceEmbedder(this)
            faceDetector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setMinFaceSize(0.08f)
                    .enableTracking()
                    .build()
            )
        } catch (e: Exception) {
            modelError = e.message ?: "model error"
            speaker.say("Error loading detection model", true)
        }
    }

    private fun analyze(proxy: ImageProxy) {
        val frame: Bitmap
        try {
            val raw = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            frame = if (rot != 0) {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            } else raw
        } finally {
            proxy.close()
        }
        ensureModels()
        val od = objectDetector ?: return
        val fd = faceDetector ?: return
        val emb = embedder ?: return
        val now = System.currentTimeMillis()
        val w = frame.width.toFloat(); val h = frame.height.toFloat()

        // ---- faces ----
        val faces = try { Tasks.await(fd.process(InputImage.fromBitmap(frame, 0))) } catch (e: Exception) { emptyList() }
        val hits = mutableListOf<FaceHit>()
        val seen = HashSet<Int>()

        if (enrolling) { handleEnroll(frame, faces, now); return }

        for (f in faces.sortedByDescending { it.boundingBox.width() }.take(3)) {
            val id = f.trackingId ?: -1
            seen += id
            val t = tracks.getOrPut(id) { Track(null, 0L, now) }
            val bigEnough = f.boundingBox.width() > w * 0.10f
            if (bigEnough && now - t.lastEmbed > 900 && store.people.isNotEmpty()) {
                emb.embed(frame, f.boundingBox)?.let { e ->
                    val m = store.match(e)
                    t.name = if (m != null && m.second >= MATCH_THRESHOLD) m.first else null
                }
                t.lastEmbed = now
            }
            val b = f.boundingBox
            hits += FaceHit(t.name, RectF(b.left / w, b.top / h, b.right / w, b.bottom / h), now - t.firstSeen)
        }
        tracks.keys.retainAll(seen)

        // ---- objects ----
        val dets = try {
            od.detect(TensorImage.fromBitmap(frame)).mapNotNull { d ->
                val c = d.categories.firstOrNull() ?: return@mapNotNull null
                val r = d.boundingBox
                Det(c.label, RectF(r.left / w, r.top / h, r.right / w, r.bottom / h), c.score)
            }
        } catch (e: Exception) { emptyList() }

        val msg = if (paused) "Paused" else guide.update(dets, hits, now)

        val boxes = hits.map { Box(it.rect, it.name ?: "Unknown", Color.GREEN) } +
            dets.filter { hits.isEmpty() || it.label != "person" }.map { Box(it.rect, it.label, Color.YELLOW) }
        overlay.update(boxes, frame.width, frame.height)
        main.post { status.text = msg }
    }

    // ------------------------------------------------------------------ enrolment

    private fun startEnroll() {
        if (enrolling) return
        samples.clear()
        enrollStart = System.currentTimeMillis()
        lastSample = 0L
        enrolling = true
        status.text = "Adding person… point camera at their face"
        speaker.say("Point the camera at the person's face and hold still.", true)
    }

    private fun handleEnroll(frame: Bitmap, faces: List<com.google.mlkit.vision.face.Face>, now: Long) {
        val w = frame.width.toFloat(); val h = frame.height.toFloat()
        val f = faces.maxByOrNull { it.boundingBox.width() }
        if (f != null) {
            val b = f.boundingBox
            overlay.update(listOf(Box(RectF(b.left / w, b.top / h, b.right / w, b.bottom / h), "Capturing ${samples.size}/$SAMPLES_NEEDED", Color.CYAN)), frame.width, frame.height)
            if (now - enrollStart > 1500 && now - lastSample > 300 && b.width() > w * 0.15f) {
                embedder?.embed(frame, b)?.let { samples += it; lastSample = now; speaker.buzz(40, 100) }
            }
        } else {
            overlay.update(emptyList(), frame.width, frame.height)
        }
        if (samples.size >= SAMPLES_NEEDED) {
            enrolling = false
            main.post { askName() }
        } else if (now - enrollStart > 12_000) {
            enrolling = false
            main.post {
                status.text = "No face found"
                speaker.say(if (f == null) "No face found. Try again." else "Face too far. Move closer and try again.", true)
            }
        }
    }

    private fun askName() {
        status.text = "Say the person's name"
        speaker.say("Face captured. Say the person's name after the beep.", true)
        main.postDelayed({
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Say the person's name")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            try { speechForName.launch(i) } catch (e: ActivityNotFoundException) { typeNameDialog() }
        }, 2600)
    }

    private fun typeNameDialog() {
        if (samples.isEmpty()) return
        val input = EditText(this).apply {
            hint = "Person's name"; inputType = InputType.TYPE_TEXT_FLAG_CAP_WORDS; textSize = 22f
        }
        AlertDialog.Builder(this)
            .setTitle("Name of this person")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val n = cleanName(input.text.toString())
                if (n.isNotEmpty()) savePerson(n) else speaker.say("Not saved", true)
            }
            .setNegativeButton("Cancel") { _, _ -> samples.clear(); speaker.say("Cancelled", true) }
            .show()
    }

    private fun savePerson(name: String) {
        val copy = samples.toList()
        samples.clear()
        analysisExecutor.execute { store.add(name, copy); tracks.clear() }
        status.text = "Saved $name"
        speaker.say("Saved $name. I will tell you when $name is in front of you.", true)
    }

    private fun cleanName(s: String) =
        s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(3)
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    // ------------------------------------------------------------------ other actions

    private fun describe() {
        val text = guide.describe()
        status.text = text
        speaker.say(text, true)
    }

    private fun togglePause() {
        paused = !paused
        btnPause.text = if (paused) "Resume" else "Pause"
        if (paused) { speaker.stop(); speaker.say("Guidance paused", true) } else speaker.say("Guidance on", true)
    }

    private fun peopleDialog() {
        val names = store.people.map { it.name }
        if (names.isEmpty()) { speaker.say("No people saved yet. Press Add Person.", true); return }
        speaker.say("${names.size} people saved: ${names.joinToString(", ")}. Tap a name to delete it.", true)
        AlertDialog.Builder(this)
            .setTitle("Saved people (tap to delete)")
            .setItems(names.toTypedArray()) { _, which ->
                val n = names[which]
                AlertDialog.Builder(this)
                    .setTitle("Delete $n?")
                    .setPositiveButton("Delete") { _, _ ->
                        analysisExecutor.execute { store.remove(n); tracks.clear() }
                        speaker.say("Deleted $n", true)
                    }
                    .setNegativeButton("Keep", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onPause() { super.onPause(); speaker.stop() }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.execute {
            objectDetector?.close(); embedder?.close(); faceDetector?.close()
        }
        analysisExecutor.shutdown()
        speaker.shutdown()
    }
}
