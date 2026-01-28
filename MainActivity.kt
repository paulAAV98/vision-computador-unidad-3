package com.example.shapesignatureapp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.math.max

class MainActivity : ComponentActivity() {

    companion object {
        init {
            System.loadLibrary("c++_shared")
            System.loadLibrary("opencv_java4")
            System.loadLibrary("native-lib")
        }
    }

    // ===== JNI =====
    external fun nativeHello(): String
    external fun nativeBinarize(argbPixels: IntArray, width: Int, height: Int): IntArray
    external fun nativeFindContour(argbPixels: IntArray, width: Int, height: Int): IntArray
    external fun nativeShapeSignatureComplex(argbPixels: IntArray, width: Int, height: Int): FloatArray
    external fun nativeFFTNormalizeFromSignature(signatureComplex: FloatArray, K: Int): FloatArray
    external fun nativeEuclideanDistance(a: FloatArray, b: FloatArray): Float

    // ===== Datos =====
    data class Sample(val label: String, val descriptor: FloatArray)

    private var originalBitmap: Bitmap? = null
    private var lastDescriptor: FloatArray? = null

    private var selectedLabel: String? = null
    private val dataset = mutableListOf<Sample>()

    private fun counts(): Triple<Int, Int, Int> {
        val t = dataset.count { it.label == "triangulo" }
        val c = dataset.count { it.label == "cuadrado" }
        val o = dataset.count { it.label == "circulo" }
        return Triple(t, c, o)
    }

    private fun updateDatasetStatus(tvStatus: TextView) {
        val (t, c, o) = counts()
        tvStatus.text = "Dataset ✅ total=${dataset.size} | triángulos=$t | cuadrados=$c | círculos=$o"
    }

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val tvFFT = findViewById<TextView>(R.id.tvFFT)
        val tvLabel = findViewById<TextView>(R.id.tvSelectedLabel)
        val tvEval = findViewById<TextView>(R.id.tvEval)

        val btnProcess = findViewById<Button>(R.id.btnProcess)
        val btnAddSample = findViewById<Button>(R.id.btnAddSample)
        val btnEvaluate = findViewById<Button>(R.id.btnEvaluate)

        if (uri == null) {
            tvFFT.text = "K=32 | Primeros 5 coef: -"
            tvLabel.text = "Etiqueta seleccionada: ${selectedLabel ?: "-"}"
            tvEval.text = "Aún no evaluado."
            btnProcess.isEnabled = false
            btnAddSample.isEnabled = false
            btnEvaluate.isEnabled = dataset.size >= 2
            if (dataset.isNotEmpty()) updateDatasetStatus(tvStatus) else tvStatus.text = "No se seleccionó imagen"
            return@registerForActivityResult
        }

        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        contentResolver.openInputStream(uri)?.use { input ->
            val bitmap = BitmapFactory.decodeStream(input)
            if (bitmap == null) {
                tvFFT.text = "K=32 | Primeros 5 coef: -"
                tvEval.text = "Aún no evaluado."
                btnProcess.isEnabled = false
                btnAddSample.isEnabled = false
                btnEvaluate.isEnabled = dataset.size >= 2
                tvStatus.text = "No se pudo leer la imagen"
                return@registerForActivityResult
            }

            originalBitmap = bitmap
            lastDescriptor = null

            findViewById<ImageView>(R.id.imgOriginal).setImageBitmap(bitmap)
            findViewById<ImageView>(R.id.imgBinary).setImageBitmap(null)
            findViewById<ImageView>(R.id.imgContour).setImageBitmap(null)

            tvStatus.text = "Imagen cargada ✅ (lista para procesar)"
            tvFFT.text = "K=32 | Primeros 5 coef: -"
            tvEval.text = "Aún no evaluado."
            btnProcess.isEnabled = true
            btnAddSample.isEnabled = false
            btnEvaluate.isEnabled = dataset.size >= 2
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val tvFFT = findViewById<TextView>(R.id.tvFFT)
        val tvLabel = findViewById<TextView>(R.id.tvSelectedLabel)
        val tvEval = findViewById<TextView>(R.id.tvEval)

        val btnPick = findViewById<Button>(R.id.btnPick)
        val btnProcess = findViewById<Button>(R.id.btnProcess)
        val btnLabelTri = findViewById<Button>(R.id.btnLabelTri)
        val btnLabelCua = findViewById<Button>(R.id.btnLabelCua)
        val btnLabelCir = findViewById<Button>(R.id.btnLabelCir)
        val btnAddSample = findViewById<Button>(R.id.btnAddSample)
        val btnEvaluate = findViewById<Button>(R.id.btnEvaluate)

        tvStatus.text = nativeHello()
        tvFFT.text = "K=32 | Primeros 5 coef: -"
        tvLabel.text = "Etiqueta seleccionada: -"
        tvEval.text = "Aún no evaluado."

        btnPick.setOnClickListener {
            pickImage.launch(arrayOf("image/*"))
        }

        // Selección de etiqueta
        btnLabelTri.setOnClickListener {
            selectedLabel = "triangulo"
            tvLabel.text = "Etiqueta seleccionada: triángulo"
            btnAddSample.isEnabled = (lastDescriptor != null)
        }

        btnLabelCua.setOnClickListener {
            selectedLabel = "cuadrado"
            tvLabel.text = "Etiqueta seleccionada: cuadrado"
            btnAddSample.isEnabled = (lastDescriptor != null)
        }

        btnLabelCir.setOnClickListener {
            selectedLabel = "circulo"
            tvLabel.text = "Etiqueta seleccionada: círculo"
            btnAddSample.isEnabled = (lastDescriptor != null)
        }

        // Procesar A/B/C
        btnProcess.setOnClickListener {
            val bmp = originalBitmap
            if (bmp == null) {
                tvStatus.text = "Primero selecciona una imagen"
                tvFFT.text = "K=32 | Primeros 5 coef: -"
                btnAddSample.isEnabled = false
                return@setOnClickListener
            }

            val w = bmp.width
            val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)

            // 1) BINARIZACIÓN
            val binPixels = nativeBinarize(pixels, w, h)
            val binBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            binBmp.setPixels(binPixels, 0, w, 0, 0, w, h)
            findViewById<ImageView>(R.id.imgBinary).setImageBitmap(binBmp)

            // 2) CONTORNO
            val contPixels = nativeFindContour(pixels, w, h)
            val contBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            contBmp.setPixels(contPixels, 0, w, 0, 0, w, h)
            findViewById<ImageView>(R.id.imgContour).setImageBitmap(contBmp)

            // 3) Firma compleja
            val sig = nativeShapeSignatureComplex(pixels, w, h)
            val nPts = sig.size / 2

            // 4) FFT + normalización
            val K = 32
            val fd = nativeFFTNormalizeFromSignature(sig, K)
            lastDescriptor = fd

            // Mostrar resumen (A/B)
            val preview = buildString {
                append("Contorno ✅ | Firma compleja ✅\n")
                append("N=$nPts | Primeros 5: ")
                val k = minOf(5, nPts)
                for (i in 0 until k) {
                    val re = sig[2 * i]
                    val im = sig[2 * i + 1]
                    append("(${String.format("%.1f", re)}, ${String.format("%.1f", im)}) ")
                }
            }

            // Mostrar resumen FFT
            val previewFFT = buildString {
                append("K=$K | Primeros 5 coef: ")
                val kShow = minOf(5, fd.size)
                for (i in 0 until kShow) {
                    append(String.format("%.3f", fd[i])).append(" ")
                }
            }

            // Si ya hay dataset, muéstralo también para que veas lo guardado
            if (dataset.isNotEmpty()) {
                updateDatasetStatus(tvStatus)
            } else {
                tvStatus.text = preview
            }
            tvFFT.text = previewFFT

            // Habilitar agregar si ya hay etiqueta
            btnAddSample.isEnabled = (selectedLabel != null && lastDescriptor != null)
        }

        // Agregar ejemplo al dataset
        btnAddSample.setOnClickListener {
            val label = selectedLabel
            val desc = lastDescriptor

            if (label == null) {
                tvStatus.text = "Selecciona etiqueta antes de agregar."
                return@setOnClickListener
            }

            if (desc == null || desc.isEmpty()) {
                tvStatus.text = "Primero procesa la imagen para obtener el FFT."
                return@setOnClickListener
            }

            dataset.add(Sample(label = label, descriptor = desc))

            // Mostrar conteo (aquí es donde “ves lo que guardas”)
            updateDatasetStatus(tvStatus)

            // Habilitar evaluación cuando haya al menos 2
            btnEvaluate.isEnabled = dataset.size >= 2

            // Evitar duplicar la misma imagen sin volver a procesar otra
            btnAddSample.isEnabled = false
        }

        // Evaluación: Leave-One-Out 1-NN con distancia Euclídea
        btnEvaluate.setOnClickListener {
            if (dataset.size < 2) {
                tvEval.text = "Necesitas al menos 2 muestras para evaluar."
                return@setOnClickListener
            }

            var correct = 0
            val total = dataset.size

            val labels = listOf("triangulo", "cuadrado", "circulo")
            val confusion = HashMap<String, HashMap<String, Int>>()
            for (a in labels) {
                confusion[a] = HashMap()
                for (p in labels) confusion[a]!![p] = 0
            }

            for (i in 0 until total) {
                val test = dataset[i]
                var bestDist = Float.POSITIVE_INFINITY
                var bestLabel: String? = null

                for (j in 0 until total) {
                    if (j == i) continue
                    val train = dataset[j]

                    val d = nativeEuclideanDistance(test.descriptor, train.descriptor)
                    if (d >= 0f && d < bestDist) {
                        bestDist = d
                        bestLabel = train.label
                    }
                }

                val pred = bestLabel ?: "?"
                if (pred == test.label) correct++

                if (confusion.containsKey(test.label) && confusion[test.label]!!.containsKey(pred)) {
                    confusion[test.label]!![pred] = (confusion[test.label]!![pred] ?: 0) + 1
                }
            }

            val acc = (correct.toFloat() / max(1, total).toFloat()) * 100f

            val report = buildString {
                append("Evaluación (Leave-One-Out 1-NN + Euclídea)\n")
                append("Total: $total | Correctos: $correct | Precisión: ${String.format("%.1f", acc)}%\n\n")
                append("Matriz de confusión (real -> predicho)\n")
                append("triangulo: ${confusion["triangulo"]!!["triangulo"]}  ${confusion["triangulo"]!!["cuadrado"]}  ${confusion["triangulo"]!!["circulo"]}\n")
                append("cuadrado : ${confusion["cuadrado"]!!["triangulo"]}  ${confusion["cuadrado"]!!["cuadrado"]}  ${confusion["cuadrado"]!!["circulo"]}\n")
                append("circulo  : ${confusion["circulo"]!!["triangulo"]}  ${confusion["circulo"]!!["cuadrado"]}  ${confusion["circulo"]!!["circulo"]}\n")
            }

            tvEval.text = report
        }
    }
}
