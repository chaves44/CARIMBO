package br.com.archaves.carimbo

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.hardware.camera2.CameraManager
import androidx.camera.camera2.interop.Camera2Interop
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Câmera do CARIMBO, no estilo do Timestamp Camera: abre direto na câmera, mostra o carimbo
 * ao vivo e grava a foto já carimbada em 4:3, 1600 x 1200 px, comprimida, na pasta da OS
 * (Imagens/Carimbo/OS <número> <cidade> <bairro>).
 */
class CameraActivity : ComponentActivity() {

    companion object {
        // níveis de qualidade: lado maior (px) e JPEG. Padrão "Boa".
        val NIVEIS = listOf(Triple("Leve", 1600, 82), Triple("Boa", 2048, 90), Triple("Máxima", 2560, 94))
        const val RAIO_OS = 300.0        // m: sugere a OS mais próxima até essa distância (mais a margem do GPS)
        const val MARGEM_GPS_MAX = 100.0 // m: quanto da imprecisão do GPS entra na margem
        const val AMBIGUA = 150.0        // m: outra OS até essa distância a mais = avisa que há duas perto
        const val SOLTAR_OS = 800.0      // m: longe disso da OS confirmada, a próxima foto volta a perguntar
        const val REQ = 31
        private val MESES = arrayOf("jan.", "fev.", "mar.", "abr.", "mai.", "jun.", "jul.", "ago.", "set.", "out.", "nov.", "dez.")
    }

    // ---------- tela ----------
    private lateinit var previa: PreviewView
    private lateinit var caixaPrevia: FrameLayout
    private lateinit var carimboTxt: TextView
    private lateinit var camada: FrameLayout      // área da foto na tela, girada junto com o celular
    private lateinit var faixaCima: View
    private lateinit var faixaBaixo: View
    private var largPrev = 0
    private var altPrev = 0
    private lateinit var osTxt: TextView
    private lateinit var gpsTxt: TextView
    private lateinit var miniatura: ImageView
    private lateinit var lentes: LinearLayout
    private lateinit var flash: View
    private val botoesZoom = mutableMapOf<String, TextView>()

    // ---------- câmera ----------
    private var provedor: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var captura: ImageCapture? = null
    private var infoPrincipal: CameraInfo? = null
    private var infoUltra: CameraInfo? = null
    private var lente = "1"
    private var frontal = false                 // câmera de selfie
    private var infoFrontal: CameraInfo? = null
    private lateinit var textura: TextureView
    private var c2: Camera2Ultra? = null
    private var fisicoUltra: String? = null     // lente 0,6 dentro da câmera lógica principal
    private var ocultoUltra: String? = null     // lente 0,6 escondida da lista, aberta pelo número
    private var grausAparelho = 0
    private var diag = StringBuilder()
    private val executor = Executors.newSingleThreadExecutor()
    private var rotacao = Surface.ROTATION_0
    private val som = MediaActionSound()

    // ---------- local e OS ----------
    private val principal = Handler(Looper.getMainLooper())
    private var local: Location? = null
    private var gerente: LocationManager? = null
    private var endereco: Pair<String, String>? = null      // bairro, cidade
    private var enderecoEm: Location? = null
    private var buscandoEndereco = false
    private var lista = JSONArray()
    private var os: JSONObject? = null          // OS em uso (escolhida ou confirmada)
    private var osManual = false                // true: não sugere outra pelo GPS
    private var osPorGps = false                // a OS em uso veio de confirmação pela sugestão do GPS
    private var sugestao: JSONObject? = null    // OS mais próxima, ainda não confirmada
    private var aguardandoOs = false            // foto tirada esperando a confirmação da OS
    private var ultimaFoto: Uri? = null
    private val prefs by lazy { getSharedPreferences("camera", MODE_PRIVATE) }
    private var nivel = 1
    private val LADO get() = NIVEIS[nivel].second
    private val QUALIDADE get() = NIVEIS[nivel].third
    private lateinit var qualTxt: TextView

    private val ouvinte = LocationListener { l ->
        val a = local
        if (a == null || l.time - a.time > 60_000 || l.accuracy <= a.accuracy + 15) local = l
        soltarOsSeLonge()
        if (!osManual) escolherOsPerto()
        atualizarEndereco()
    }

    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        montarTela()
        lista = try { JSONArray(prefs.getString("lista", "[]")) } catch (_: Exception) { JSONArray() }
        lerIntent(intent)
        pedirPermissoes()
        orientacao.enable()
        principal.post(relogio)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        lerIntent(intent)
    }

    /** O app OS CAIXA abre a câmera mandando a OS e a lista das OS abertas. */
    private fun lerIntent(i: Intent?) {
        i ?: return
        i.getStringExtra("lista")?.let { s ->
            try { lista = JSONArray(s); prefs.edit().putString("lista", s).apply() } catch (_: Exception) {}
        }
        i.getStringExtra("os")?.let { n ->
            val o = acharOs(n)
            if (o != null) { os = o; osManual = true; osPorGps = false }
        }
        mostrarOs()
    }

    override fun onResume() { super.onResume(); ligarGps(); lerUltima(); if (provedor != null && !frontal && lente == "0.6" && ocultoUltra != null && textura.visibility == View.VISIBLE) usarCamera2(ocultoUltra!!) }
    override fun onPause() { super.onPause(); desligarGps(); if (c2 != null) { c2?.fechar(); c2 = null } }
    override fun onDestroy() {
        orientacao.disable(); principal.removeCallbacks(relogio); executor.shutdown(); som.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- tela

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Int) = dp(v.toFloat()).roundToInt()

    private fun pilula(txt: String, fundo: Int = 0x66000000, cor: Int = Color.WHITE): TextView = TextView(this).apply {
        text = txt; setTextColor(cor); textSize = 14f; gravity = Gravity.CENTER
        setPadding(dpi(12), dpi(7), dpi(12), dpi(7))
        background = GradientDrawable().apply { cornerRadius = dp(20f); setColor(fundo) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun montarTela() {
        val raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val larg = resources.displayMetrics.widthPixels
        val alt = larg * 4 / 3

        // prévia 3:4 (a mesma área que vai para a foto)
        caixaPrevia = FrameLayout(this)
        previa = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        caixaPrevia.addView(previa, FrameLayout.LayoutParams(-1, -1))
        textura = TextureView(this).apply { visibility = View.GONE }
        caixaPrevia.addView(textura, FrameLayout.LayoutParams(-1, -1))
        largPrev = larg; altPrev = alt
        val faixa = (alt - larg * 3 / 4) / 2
        faixaCima = View(this).apply { setBackgroundColor(Color.BLACK) }
        faixaBaixo = View(this).apply { setBackgroundColor(Color.BLACK) }
        caixaPrevia.addView(faixaCima, FrameLayout.LayoutParams(-1, faixa, Gravity.TOP))
        caixaPrevia.addView(faixaBaixo, FrameLayout.LayoutParams(-1, faixa, Gravity.BOTTOM))
        camada = FrameLayout(this)
        caixaPrevia.addView(camada, FrameLayout.LayoutParams(larg, larg * 3 / 4, Gravity.CENTER))
        carimboTxt = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.END
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
            setPadding(dpi(8), dpi(8), dpi(10), dpi(8))
        }
        camada.addView(carimboTxt, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
        flash = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f }
        caixaPrevia.addView(flash, FrameLayout.LayoutParams(-1, -1))
        val topo = dpi(56)
        raiz.addView(caixaPrevia, FrameLayout.LayoutParams(larg, alt).apply { topMargin = topo })

        // barra de cima: OS e GPS
        val barra = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(10), dpi(8), dpi(10), dpi(8)) }
        osTxt = pilula("Sem OS", 0xFF005CA9.toInt()).apply { maxLines = 1; setOnClickListener { escolherOsManual() } }
        gpsTxt = TextView(this).apply { setTextColor(0xFFBBBBBB.toInt()); textSize = 12f; gravity = Gravity.END }
        barra.addView(osTxt, LinearLayout.LayoutParams(0, -2, 1f))
        barra.addView(gpsTxt, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dpi(8) })
        nivel = prefs.getInt("nivel", 1)
        qualTxt = pilula(NIVEIS[nivel].first).apply {
            textSize = 12f; setPadding(dpi(10), dpi(6), dpi(10), dpi(6))
            setOnClickListener {
                nivel = (nivel + 1) % NIVEIS.size; prefs.edit().putInt("nivel", nivel).apply()
                text = NIVEIS[nivel].first
                Toast.makeText(this@CameraActivity, "Qualidade ${NIVEIS[nivel].first}: ${NIVEIS[nivel].second} x ${NIVEIS[nivel].second * 3 / 4} px", Toast.LENGTH_SHORT).show()
            }
        }
        barra.addView(qualTxt, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dpi(8) })
        raiz.addView(barra, FrameLayout.LayoutParams(-1, topo, Gravity.TOP))

        // lentes 0.6, 1x, 2x
        lentes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (k in listOf("0.6", "1", "2")) {
            val b = pilula(if (k == "0.6") "0,6" else k + "x").apply {
                textSize = 13f; setPadding(dpi(12), dpi(6), dpi(12), dpi(6))
                setOnClickListener { trocarLente(k) }
                setOnLongClickListener { mostrarDiagnostico(); true }
            }
            botoesZoom[k] = b
            lentes.addView(b, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dpi(6); rightMargin = dpi(6) })
        }
        val selfie = pilula("🤳 Selfie").apply {
            textSize = 13f; setPadding(dpi(12), dpi(6), dpi(12), dpi(6))
            setOnClickListener { trocarFrontal() }
        }
        botoesZoom["selfie"] = selfie
        lentes.addView(selfie, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dpi(14); rightMargin = dpi(6) })
        raiz.addView(lentes, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = topo + alt - dpi(48) })

        // barra de baixo: miniatura, disparo, enviar
        val baixo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(22), 0, dpi(22), 0) }
        miniatura = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(8f); setColor(0xFF333333.toInt()); setStroke(dpi(2), Color.WHITE) }
            clipToOutline = true
            setOnClickListener { abrirUltima() }
        }
        val disparo = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dpi(5), 0xFF999999.toInt()) }
            setOnClickListener { fotografar() }
        }
        val direita = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val enviar = pilula("Enviar", 0xFF25D366.toInt()).apply {
            setOnClickListener { enviarOsAtual() }
            setOnLongClickListener { escolherPastaParaEnviar(); true }
        }
        val mais = TextView(this).apply {
            text = "Editar fotos"; setTextColor(0xFFBBBBBB.toInt()); textSize = 12f; gravity = Gravity.CENTER
            setPadding(0, dpi(8), 0, 0)
            setOnClickListener { startActivity(Intent(this@CameraActivity, MainActivity::class.java)) }
        }
        direita.addView(enviar); direita.addView(mais)
        baixo.addView(miniatura, LinearLayout.LayoutParams(dpi(56), dpi(56)))
        baixo.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        baixo.addView(disparo, LinearLayout.LayoutParams(dpi(76), dpi(76)))
        baixo.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        baixo.addView(direita, LinearLayout.LayoutParams(dpi(80), -2))
        val resto = resources.displayMetrics.heightPixels - topo - alt
        raiz.addView(baixo, FrameLayout.LayoutParams(-1, max(dpi(110), resto), Gravity.BOTTOM))

        // pinça para zoom e toque para focar
        val pinca = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val c = camera ?: return true
                val z = c.cameraInfo.zoomState.value ?: return true
                c.cameraControl.setZoomRatio((z.zoomRatio * d.scaleFactor).coerceIn(z.minZoomRatio, z.maxZoomRatio))
                return true
            }
        })
        previa.setOnTouchListener { v, e ->
            pinca.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_UP && !pinca.isInProgress && e.eventTime - e.downTime < 250) {
                camera?.let { c ->
                    val p = previa.meteringPointFactory.createPoint(e.x, e.y)
                    c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(p).build())
                }
                v.performClick()
            }
            true
        }
        setContentView(raiz)
        ajustarCamada()
    }

    // ---------------------------------------------------------------- permissões

    private fun pedirPermissoes() {
        val l = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.READ_MEDIA_IMAGES) else l.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT <= 28) l.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val faltam = l.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (faltam.isEmpty()) { iniciarCamera(); ligarGps() } else requestPermissions(faltam.toTypedArray(), REQ)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) iniciarCamera()
        else Toast.makeText(this, "Sem permissão de câmera", Toast.LENGTH_LONG).show()
        ligarGps(); lerUltima()
    }

    // ---------------------------------------------------------------- câmera

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun campoVisao(i: CameraInfo): Double? = try {
        val c = Camera2CameraInfo.from(i)
        val f = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
        val s = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (f != null && s != null && f > 0) 2 * atan(s.width / (2.0 * f)) else null
    } catch (_: Exception) { null }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun traseira(i: CameraInfo): Boolean = try {
        Camera2CameraInfo.from(i).getCameraCharacteristic(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    } catch (_: Exception) { false }

    private fun iniciarCamera() {
        if (provedor != null) { ligarLente(); return }
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val p = f.get(); provedor = p
            val traseiras = p.availableCameraInfos.filter { traseira(it) }
            infoPrincipal = try {
                p.availableCameraInfos.firstOrNull { CameraSelector.DEFAULT_BACK_CAMERA.filter(listOf(it)).isNotEmpty() }
            } catch (_: Exception) { null } ?: traseiras.firstOrNull()
            val fovP = infoPrincipal?.let { campoVisao(it) }
            if (fovP != null) {
                infoUltra = traseiras.filter { it != infoPrincipal }
                    .mapNotNull { i -> campoVisao(i)?.let { i to it } }
                    .filter { it.second > fovP * 1.25 }
                    .maxByOrNull { it.second }?.first
            }
            infoFrontal = try {
                p.availableCameraInfos.firstOrNull { CameraSelector.DEFAULT_FRONT_CAMERA.filter(listOf(it)).isNotEmpty() }
            } catch (_: Exception) { null }
            procurarUltraEscondida(fovP)
            lente = prefs.getString("lente", "1") ?: "1"
            ligarLente()
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    private fun fov(c: CameraCharacteristics): Double? {
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
        val s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        return if (f != null && s != null && f > 0) 2 * atan(s.width / (2.0 * f)) else null
    }
    private fun graus(r: Double?) = r?.let { String.format(Locale.US, "%.0f°", Math.toDegrees(it)) } ?: "?"

    /** Procura a lente 0,6 de três jeitos: câmera física dentro da principal, números escondidos e propriedade da Xiaomi. */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun procurarUltraEscondida(fovP: Double?) {
        diag = StringBuilder()
        val g = getSystemService(CAMERA_SERVICE) as CameraManager
        val lista = try { g.cameraIdList.toList() } catch (_: Exception) { emptyList() }
        diag.append("Celular: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n")
        diag.append("Câmeras liberadas para apps: ${lista.joinToString(", ")}\n")
        for (id in lista) {
            try {
                val c = g.getCameraCharacteristics(id)
                val lado = if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) "traseira" else "frontal"
                val fis = if (Build.VERSION.SDK_INT >= 28) c.physicalCameraIds else emptySet()
                val z = if (Build.VERSION.SDK_INT >= 30) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower else null
                diag.append("  câmera $id: $lado, ângulo ${graus(fov(c))}${if (fis.isNotEmpty()) ", lentes internas ${fis.joinToString(",")}" else ""}${if (z != null) ", zoom mínimo $z" else ""}\n")
            } catch (e: Exception) { diag.append("  câmera $id: erro ${e.message}\n") }
        }
        // 1) lente física dentro da câmera lógica principal
        val idPrincipal = try { infoPrincipal?.let { Camera2CameraInfo.from(it).cameraId } } catch (_: Exception) { null }
        if (infoUltra == null && idPrincipal != null && Build.VERSION.SDK_INT >= 28) {
            try {
                for (f in g.getCameraCharacteristics(idPrincipal).physicalCameraIds) {
                    val a = fov(g.getCameraCharacteristics(f))
                    diag.append("  lente interna $f: ângulo ${graus(a)}\n")
                    if (a != null && fovP != null && a > fovP * 1.25) fisicoUltra = f
                }
            } catch (_: Exception) {}
        }
        // 2) números de câmera que não aparecem na lista
        for (n in 0..15) {
            val id = n.toString()
            if (id in lista) continue
            try {
                val c = g.getCameraCharacteristics(id)
                val a = fov(c)
                val tras = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                diag.append("  escondida $id: ${if (tras) "traseira" else "frontal"}, ângulo ${graus(a)}\n")
                if (tras && a != null && fovP != null && a > fovP * 1.25 && ocultoUltra == null) ocultoUltra = id
            } catch (_: Exception) {}
        }
        // 3) lista de apps que a Xiaomi libera para as lentes extras
        val prop = try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "vendor.camera.aux.packagelist") as String
        } catch (_: Exception) { "" }
        diag.append("Apps liberados pela Xiaomi: ${prop.ifBlank { "(não deu para ler)" }}\n")
        diag.append("Ângulo da principal: ${graus(fovP)}\n")
        diag.append("Lente 0,6: " + when {
            infoUltra != null -> "liberada direto"
            fisicoUltra != null -> "lente interna $fisicoUltra"
            ocultoUltra != null -> "escondida, número $ocultoUltra"
            else -> "não achei"
        } + "\n")
    }

    private fun mostrarDiagnostico() {
        AlertDialog.Builder(this).setTitle("Lentes deste celular").setMessage(diag.toString())
            .setPositiveButton("OK", null)
            .setNeutralButton("Copiar") { _, _ ->
                val cb = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("lentes", diag.toString()))
                Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun usarCamera2(id: String) {
        provedor?.unbindAll(); camera = null; captura = null
        previa.visibility = View.GONE; textura.visibility = View.VISIBLE
        c2?.fechar()
        c2 = Camera2Ultra(this, id, textura) { m ->
            principal.post { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }
        }.also { it.abrir() }
    }

    private fun sairCamera2() {
        c2?.fechar(); c2 = null
        textura.visibility = View.GONE; previa.visibility = View.VISIBLE
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun ligarLente() {
        val p = provedor ?: return
        if (frontal) { ligarFrontal(p); return }
        if (lente == "0.6" && infoUltra == null && fisicoUltra == null && ocultoUltra != null &&
            (infoPrincipal?.zoomState?.value?.minZoomRatio ?: 1f) >= 0.95f) {
            usarCamera2(ocultoUltra!!); pintarBotoes(); return
        }
        sairCamera2()
        val usarUltra = lente == "0.6" && infoUltra != null
        val usarFisica = lente == "0.6" && !usarUltra && fisicoUltra != null
        val info = (if (usarUltra) infoUltra else infoPrincipal) ?: return
        val res = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(4624, 3468), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val pb = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
        val cb = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(res)
            .setJpegQuality(97)
            .setTargetRotation(rotacao)
        if (usarFisica) {
            Camera2Interop.Extender(pb).setPhysicalCameraId(fisicoUltra!!)
            Camera2Interop.Extender(cb).setPhysicalCameraId(fisicoUltra!!)
        }
        val prev = pb.build().also { it.setSurfaceProvider(previa.surfaceProvider) }
        val cap = cb.build()
        try {
            p.unbindAll()
            camera = p.bindToLifecycle(this, info.cameraSelector, prev, cap)
            captura = cap
            val c = camera!!
            val minZ = c.cameraInfo.zoomState.value?.minZoomRatio ?: 1f
            when {
                lente == "0.6" && !usarUltra && !usarFisica && minZ < 0.95f -> c.cameraControl.setZoomRatio(max(0.6f, minZ))
                lente == "2" -> c.cameraControl.setZoomRatio(2f)
                else -> c.cameraControl.setZoomRatio(1f)
            }
            if (lente == "0.6" && !usarUltra && !usarFisica && minZ >= 0.95f) {
                Toast.makeText(this, "Não achei como abrir a lente 0,6 neste celular. Segure o botão 0,6 para ver o diagnóstico.", Toast.LENGTH_LONG).show()
                lente = "1"
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Erro na câmera: ${e.message}", Toast.LENGTH_LONG).show()
        }
        pintarBotoes()
    }

    /** Câmera de selfie: sem 0,6 nem 2x; a foto sai do mesmo jeito (paisagem 4:3, carimbada). */
    private fun ligarFrontal(p: ProcessCameraProvider) {
        val info = infoFrontal
        if (info == null) {
            Toast.makeText(this, "Não achei a câmera de selfie", Toast.LENGTH_SHORT).show()
            frontal = false; ligarLente(); return
        }
        sairCamera2()
        val prev = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
            .build().also { it.setSurfaceProvider(previa.surfaceProvider) }
        val cap = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build())
            .setJpegQuality(97)
            .setTargetRotation(rotacao)
            .build()
        try {
            p.unbindAll()
            camera = p.bindToLifecycle(this, info.cameraSelector, prev, cap)
            captura = cap
        } catch (e: Exception) {
            Toast.makeText(this, "Erro na câmera de selfie: ${e.message}", Toast.LENGTH_LONG).show()
        }
        pintarBotoes()
    }

    private fun trocarFrontal() {
        frontal = !frontal
        ligarLente()
    }

    private fun pintarBotoes() {
        val tem06 = infoUltra != null || fisicoUltra != null || ocultoUltra != null || ((infoPrincipal?.zoomState?.value?.minZoomRatio ?: 1f) < 0.95f)
        botoesZoom["0.6"]?.alpha = if (tem06) 1f else 0.35f
        for ((k, b) in botoesZoom) {
            val on = if (k == "selfie") frontal else (!frontal && k == lente)
            (b.background as GradientDrawable).setColor(if (on) 0xFFF39200.toInt() else 0x66000000)
        }
    }

    private fun trocarLente(k: String) {
        if (frontal) { frontal = false; lente = k; prefs.edit().putString("lente", k).apply(); ligarLente(); return }
        if (k == lente) return
        lente = k; prefs.edit().putString("lente", k).apply()
        ligarLente()
    }

    private val orientacao by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(g: Int) {
                if (g == ORIENTATION_UNKNOWN) return
                val ga = ((g + 45) / 90 * 90) % 360
                if (ga != grausAparelho) { grausAparelho = ga; ajustarCamada() }
                val r = when (g) { in 45..134 -> Surface.ROTATION_270; in 135..224 -> Surface.ROTATION_180; in 225..314 -> Surface.ROTATION_90; else -> Surface.ROTATION_0 }
                if (r != rotacao) {
                    rotacao = r; captura?.targetRotation = r
                    val graus = when (r) { Surface.ROTATION_90 -> 90f; Surface.ROTATION_270 -> -90f; Surface.ROTATION_180 -> 180f; else -> 0f }
                    miniatura.animate().rotation(graus).start()
                    for (b in botoesZoom.values) b.animate().rotation(graus).start()
                }
            }
        }
    }

    /**
     * A foto sai sempre deitada (paisagem 4:3). Com o celular em pé, a tela escurece em cima e embaixo
     * e mostra só a faixa que vai virar foto; com o celular deitado, a foto é a tela toda.
     * O carimbo gira junto para ficar no canto de baixo à direita da foto.
     */
    private fun ajustarCamada() {
        val emPe = grausAparelho == 0 || grausAparelho == 180
        faixaCima.visibility = if (emPe) View.VISIBLE else View.GONE
        faixaBaixo.visibility = if (emPe) View.VISIBLE else View.GONE
        val lp = camada.layoutParams as FrameLayout.LayoutParams
        if (emPe) { lp.width = largPrev; lp.height = largPrev * 3 / 4 } else { lp.width = altPrev; lp.height = largPrev }
        lp.gravity = Gravity.CENTER
        camada.layoutParams = lp
        var r = -grausAparelho.toFloat()
        if (r <= -180f) r += 360f
        camada.rotation = r
    }

    // ---------------------------------------------------------------- foto

    private fun fotografar() {
        if (aguardandoOs) { Toast.makeText(this, "Confirme antes a OS da foto anterior", Toast.LENGTH_SHORT).show(); return }
        val ultra = c2
        val cap = captura
        if (ultra == null && cap == null) return
        val quando = Date()
        val loc = local
        val osAgora = os
        som.play(MediaActionSound.SHUTTER_CLICK)
        flash.alpha = 0.8f; flash.animate().alpha(0f).setDuration(250).start()
        val semPerguntar = osAgora != null || osManual      // OS escolhida, ou "Sem OS" escolhido de propósito
        if (!semPerguntar) aguardandoOs = true
        val depois: (Bitmap) -> Unit = { bmp ->
            if (semPerguntar) salvarFoto(bmp, quando, loc, osAgora)
            else principal.post { confirmarOs(bmp, quando, loc) }
        }
        if (ultra != null) {
            ultra.fotografar(grausAparelho) { bytes, rot ->
                executor.execute {
                    try { depois(bytesParaBitmap(bytes, rot)) }
                    catch (e: Throwable) { aguardandoOs = false; principal.post { Toast.makeText(this, "Erro: ${e.message}", Toast.LENGTH_LONG).show() } }
                }
            }
            return
        }
        cap!!.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val bmp = paraBitmap(image)
                    image.close()
                    depois(bmp)
                } catch (e: Throwable) {
                    aguardandoOs = false
                    try { image.close() } catch (_: Exception) {}
                    principal.post { Toast.makeText(this@CameraActivity, "Erro: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
            override fun onError(e: ImageCaptureException) {
                aguardandoOs = false
                principal.post { Toast.makeText(this@CameraActivity, "Erro ao fotografar: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        })
    }

    /** Grava (fora da tela principal) e atualiza a miniatura. */
    private fun salvarFoto(bmp: Bitmap, quando: Date, loc: Location?, o: JSONObject?) {
        executor.execute {
            try {
                val uri = gravar(bmp, quando, loc, o, textoLocal(loc, o))
                principal.post { if (uri != null) { ultimaFoto = uri; lerUltima() } else Toast.makeText(this, "Não consegui salvar a foto", Toast.LENGTH_LONG).show() }
            } catch (e: Throwable) {
                principal.post { Toast.makeText(this, "Erro: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun metros(d: Double): String =
        if (d < 1000) "${d.roundToInt()} m" else String.format(Locale("pt", "BR"), "%.1f km", d / 1000)

    /**
     * Foto tirada sem OS escolhida: sugere a OS aberta mais próxima, com margem de segurança,
     * e só salva depois que André confirmar.
     */
    private fun confirmarOs(bmp: Bitmap, quando: Date, loc: Location?) {
        val terminar: (JSONObject?, Boolean) -> Unit = { o, lembrar ->
            aguardandoOs = false
            if (lembrar && o != null) { os = o; osManual = true; osPorGps = true; sugestao = null; mostrarOs() }
            salvarFoto(bmp, quando, loc, o)
            Toast.makeText(this, if (o == null) "Foto salva sem OS" else "Foto salva na OS ${o.optString("num")}", Toast.LENGTH_SHORT).show()
        }
        val ord = (0 until lista.length()).mapNotNull { lista.optJSONObject(it) }
            .mapNotNull { o -> loc?.let { l -> distOs(o, l) }?.let { o to it } }
            .sortedBy { it.second }
        val idade = loc?.let { System.currentTimeMillis() - it.time } ?: Long.MAX_VALUE
        val prec = loc?.accuracy?.toDouble() ?: Double.MAX_VALUE
        val gpsBom = loc != null && idade < 120_000 && prec <= MARGEM_GPS_MAX
        val limite = RAIO_OS + (if (gpsBom) prec else 0.0)
        val perto = ord.firstOrNull()?.takeIf { gpsBom && it.second <= limite }

        if (perto == null) {
            val motivo = when {
                lista.length() == 0 -> "A lista de OS está vazia. Abra o CARIMBO pelo app OS CAIXA para carregar as OS abertas."
                loc == null -> "Ainda sem GPS, não dá para saber de qual OS é a foto."
                !gpsBom -> "O GPS está impreciso (±${prec.roundToInt()} m${if (idade >= 120_000) ", leitura antiga" else ""}), não dá para sugerir a OS com segurança."
                ord.isEmpty() -> "Nenhuma OS aberta tem coordenadas."
                else -> "Nenhuma OS aberta a menos de ${metros(limite)} daqui. A mais próxima é a ${ord[0].first.optString("num")}, a ${metros(ord[0].second)}."
            }
            AlertDialog.Builder(this).setTitle("De qual OS é esta foto?").setMessage(motivo).setCancelable(false)
                .setPositiveButton("Escolher a OS") { _, _ -> escolherOsParaFoto(loc) { o -> terminar(o, o != null) } }
                .setNegativeButton("Salvar sem OS") { _, _ -> terminar(null, false) }
                .show()
            return
        }

        val (o, d) = perto
        val segunda = ord.getOrNull(1)?.takeIf { it.second - d <= AMBIGUA }
        val msg = StringBuilder()
            .append("OS ${o.optString("num")}\n")
            .append(listOf(o.optString("bairro"), o.optString("cidade")).filter { it.isNotBlank() }.joinToString(", ")).append("\n\n")
            .append("Distância: ${metros(d)} (GPS ±${prec.roundToInt()} m)")
        if (d > RAIO_OS / 2) msg.append("\n\nAtenção: o ponto da OS está a mais de ${metros(RAIO_OS / 2)}. Confira se é o imóvel certo.")
        if (segunda != null) msg.append("\n\nCuidado: a OS ${segunda.first.optString("num")} (${segunda.first.optString("bairro").ifBlank { segunda.first.optString("cidade") }}) também está perto, a ${metros(segunda.second)}.")
        AlertDialog.Builder(this).setTitle("Salvar nesta OS?").setMessage(msg.toString()).setCancelable(false)
            .setPositiveButton("Sim, é esta") { _, _ -> terminar(o, true) }
            .setNeutralButton("Outra OS") { _, _ -> escolherOsParaFoto(loc) { x -> terminar(x, x != null) } }
            .setNegativeButton("Sem OS") { _, _ -> terminar(null, false) }
            .show()
    }

    /** Lista das OS (mais perto primeiro) para a foto que está esperando. */
    private fun escolherOsParaFoto(loc: Location?, fim: (JSONObject?) -> Unit) {
        val ord = (0 until lista.length()).mapNotNull { lista.optJSONObject(it) }
            .sortedBy { o -> loc?.let { distOs(o, it) } ?: Double.MAX_VALUE }
        val nomes = ord.map { o ->
            val d = loc?.let { distOs(o, it) }
            "${o.optString("num")} · ${o.optString("cidade")} · ${o.optString("bairro")}" + (d?.let { " · " + metros(it) } ?: "")
        } + listOf("Digitar o número da OS", "Salvar sem OS")
        AlertDialog.Builder(this).setTitle("Qual OS?").setCancelable(false)
            .setItems(nomes.toTypedArray()) { _, k ->
                when {
                    k < ord.size -> fim(ord[k])
                    k == ord.size -> digitarOs { n -> fim(n) }
                    else -> fim(null)
                }
            }.show()
    }

    /** JPEG da câmera → bitmap girado, cortado em 4:3 e reduzido para 1600 x 1200 (ou 1200 x 1600 em pé). */
    private fun paraBitmap(image: ImageProxy): Bitmap {
        val buf = image.planes[0].buffer
        val bytes = ByteArray(buf.remaining()); buf.get(bytes)
        return bytesParaBitmap(bytes, image.imageInfo.rotationDegrees)
    }

    private fun bytesParaBitmap(bytes: ByteArray, rot: Int): Bitmap {
        val op = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, op)
        var amostra = 1
        while (min(op.outWidth, op.outHeight) / (amostra * 2) >= LADO) amostra *= 2
        val orig = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = amostra })
        val girada = if (rot != 0) Bitmap.createBitmap(orig, 0, 0, orig.width, orig.height, Matrix().apply { postRotate(rot.toFloat()) }, true).also { if (it !== orig) orig.recycle() } else orig
        val w = girada.width; val h = girada.height
        // sempre paisagem 4:3: em pé, corta a faixa do meio
        val (cw, ch) = if (w * 3 > h * 4) Pair(h * 4 / 3, h) else Pair(w, w * 3 / 4)
        val cortada = Bitmap.createBitmap(girada, (w - cw) / 2, (h - ch) / 2, cw, ch)
        val (fw, fh) = Pair(LADO, LADO * 3 / 4)
        val final = reduzir(cortada, fw, fh)
        if (cortada !== final && cortada !== girada) cortada.recycle()
        if (girada !== final) girada.recycle()
        return if (final.isMutable) final else final.copy(Bitmap.Config.ARGB_8888, true).also { final.recycle() }
    }

    /** Reduz pela metade em etapas antes do tamanho final: fica bem mais nítido que reduzir de uma vez. */
    private fun reduzir(b0: Bitmap, fw: Int, fh: Int): Bitmap {
        var b = b0
        while (b.width / 2 >= fw) {
            val n = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
            if (b !== b0) b.recycle()
            b = n
        }
        val f = Bitmap.createScaledBitmap(b, fw, fh, true)
        if (b !== b0 && b !== f) b.recycle()
        return f
    }

    private fun linhas(quando: Date, loc: Location?, end: Pair<String, String>?, o: JSONObject?): List<String> {
        val c = Calendar.getInstance().apply { time = quando }
        val l = mutableListOf<String>()
        l.add("${c.get(Calendar.DAY_OF_MONTH)} de ${MESES[c.get(Calendar.MONTH)]} de ${c.get(Calendar.YEAR)} " +
                SimpleDateFormat("HH:mm:ss", Locale.US).format(quando))
        if (loc != null) l.add(dms(loc.latitude, true) + " " + dms(loc.longitude, false))
        if (end != null) l.add(listOf(end.first, end.second).filter { it.isNotBlank() }.joinToString(", "))
        // o número da OS não vai escrito na foto: fica só nos metadados (EXIF) e no nome do arquivo
        return l
    }

    private fun dms(v: Double, lat: Boolean): String {
        val a = abs(v); val g = a.toInt(); val mf = (a - g) * 60; val m = mf.toInt(); val s = (mf - m) * 60
        val ss = String.format(Locale.US, "%.5f", s).replace('.', ',')
        val h = if (lat) (if (v < 0) "S" else "N") else (if (v < 0) "W" else "E")
        return "$g°$m'$ss\"$h"
    }

    /** Carimbo igual ao do Timestamp: texto branco com sombra, alinhado à direita, no canto de baixo. */
    private fun carimbar(b: Bitmap, l: List<String>) {
        val cv = Canvas(b)
        val tam = min(b.width, b.height) * 0.036f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = tam; textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            setShadowLayer(tam * 0.12f, tam * 0.05f, tam * 0.05f, 0xCC000000.toInt())
        }
        val mg = min(b.width, b.height) * 0.02f
        val passo = tam * 1.22f
        var y = b.height - mg - (l.size - 1) * passo
        for (t in l) { cv.drawText(t, b.width - mg, y, p); y += passo }
    }

    private fun nomePasta(o: JSONObject?): String {
        if (o == null) return "Sem OS"
        val partes = listOf("OS " + o.optString("num"), o.optString("cidade"), o.optString("bairro")).filter { it.isNotBlank() }
        return partes.joinToString(" ").replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun gravar(bmp0: Bitmap, quando: Date, loc: Location?, o: JSONObject?, end: Pair<String, String>?): Uri? {
        carimbar(bmp0, linhas(quando, loc, end, o))
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(quando)
        val nome = (if (o != null) "OS_" + o.optString("num") else "FOTO") + "_" + stamp + ".jpg"
        val tmp = File(cacheDir, nome)
        FileOutputStream(tmp).use { bmp0.compress(Bitmap.CompressFormat.JPEG, QUALIDADE, it) }
        bmp0.recycle()
        try {
            val ex = ExifInterface(tmp.absolutePath)
            val d = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(quando)
            ex.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, d)
            ex.setAttribute(ExifInterface.TAG_DATETIME, d)
            ex.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
            ex.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
            ex.setAttribute(ExifInterface.TAG_SOFTWARE, "Carimbo A R Chaves")
            if (o != null) {
                val desc = listOf("OS " + o.optString("num"), o.optString("cidade"), o.optString("bairro")).filter { it.isNotBlank() }.joinToString(" · ")
                ex.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, desc)
                ex.setAttribute(ExifInterface.TAG_USER_COMMENT, desc)
            }
            if (loc != null) {
                ex.setGpsInfo(loc)
            }
            ex.saveAttributes()
        } catch (_: Exception) {}
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, nome)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, quando.time)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Carimbo/" + nomePasta(o))
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Carimbo/" + nomePasta(o)).apply { mkdirs() }
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, File(dir, nome).absolutePath)
            }
        }
        val u = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
        contentResolver.openOutputStream(u)!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
        if (Build.VERSION.SDK_INT >= 29) {
            cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(u, cv, null, null)
        }
        tmp.delete()
        return u
    }

    // ---------------------------------------------------------------- miniatura e envio

    private fun lerUltima() {
        thread {
            val u = ultimaFoto ?: fotosDaPasta(null).firstOrNull()
            val b = u?.let {
                try {
                    if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(it, Size(160, 160), null)
                    else contentResolver.openInputStream(it)?.use { s -> BitmapFactory.decodeStream(s, null, BitmapFactory.Options().apply { inSampleSize = 8 }) }
                } catch (_: Exception) { null }
            }
            principal.post { if (b != null) { miniatura.setImageBitmap(b); ultimaFoto = u } }
        }
    }

    private fun abrirUltima() {
        val u = ultimaFoto ?: return
        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (_: Exception) {}
    }

    /** Fotos do CARIMBO, mais novas primeiro. pasta = null pega todas. */
    private fun fotosDaPasta(pasta: String?): List<Uri> {
        val r = mutableListOf<Uri>()
        val col = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val (sel, args) = if (Build.VERSION.SDK_INT >= 29) {
            if (pasta == null) "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" to arrayOf("Pictures/Carimbo/%")
            else "${MediaStore.Images.Media.RELATIVE_PATH} = ?" to arrayOf("Pictures/Carimbo/$pasta/")
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.Images.Media.DATA} LIKE ?" to arrayOf("%/Pictures/Carimbo/" + (pasta?.let { "$it/" } ?: "") + "%")
        }
        try {
            contentResolver.query(col, arrayOf(MediaStore.Images.Media._ID), sel, args, "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { c ->
                while (c.moveToNext()) r.add(ContentUris.withAppendedId(col, c.getLong(0)))
            }
        } catch (_: Exception) {}
        return r
    }

    /** Pastas de OS em Imagens/Carimbo com a quantidade de fotos, mais recentes primeiro. */
    private fun pastas(): List<Pair<String, Int>> {
        if (Build.VERSION.SDK_INT < 29) return listOf(nomePasta(os) to fotosDaPasta(nomePasta(os)).size)
        val cont = LinkedHashMap<String, Int>()
        try {
            contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media.RELATIVE_PATH),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("Pictures/Carimbo/%"), "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val p = c.getString(0).removePrefix("Pictures/Carimbo/").trimEnd('/')
                    if (p.isNotEmpty() && !p.contains('/')) cont[p] = (cont[p] ?: 0) + 1
                }
            }
        } catch (_: Exception) {}
        return cont.entries.map { it.key to it.value }
    }

    /** Enviar: manda direto as fotos da OS em uso. Sem OS, mostra as pastas. Segurar o botão também mostra as pastas. */
    private fun enviarOsAtual() {
        val o = os
        if (o == null) { escolherPastaParaEnviar(); return }
        val pasta = nomePasta(o)
        thread {
            val n = fotosDaPasta(pasta).size
            principal.post {
                if (n == 0) Toast.makeText(this, "A OS ${o.optString("num")} ainda não tem fotos", Toast.LENGTH_SHORT).show()
                else enviarFotos(pasta)
            }
        }
    }

    private fun escolherPastaParaEnviar() {
        thread {
            val ps = pastas()
            principal.post {
                if (ps.isEmpty()) { Toast.makeText(this, "Ainda não há fotos do CARIMBO", Toast.LENGTH_SHORT).show(); return@post }
                val atual = nomePasta(os)
                val ord = ps.sortedByDescending { it.first == atual }
                AlertDialog.Builder(this)
                    .setTitle("Qual pasta enviar?")
                    .setItems(ord.map { "${it.first} · ${it.second} ${if (it.second == 1) "foto" else "fotos"}" }.toTypedArray()) { _, k -> comoEnviar(ord[k].first) }
                    .setNegativeButton("Cancelar", null).show()
            }
        }
    }

    private fun comoEnviar(pasta: String) {
        AlertDialog.Builder(this)
            .setTitle(pasta)
            .setItems(arrayOf("Enviar as fotos (WhatsApp e outros)", "Enviar a pasta num arquivo .zip")) { _, k ->
                if (k == 0) enviarFotos(pasta) else enviarZip(pasta)
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun enviarFotos(pasta: String) {
        thread {
            val us = ArrayList(fotosDaPasta(pasta).reversed())
            principal.post {
                if (us.isEmpty()) return@post
                val i = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, us)
                    .setType("image/jpeg").putExtra(Intent.EXTRA_TEXT, pasta).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(i, "Enviar ${us.size} fotos"))
            }
        }
    }

    private fun enviarZip(pasta: String) {
        Toast.makeText(this, "Preparando o .zip…", Toast.LENGTH_SHORT).show()
        thread {
            val us = fotosDaPasta(pasta).reversed()
            val dir = File(cacheDir, "zip").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val z = File(dir, "$pasta.zip")
            try {
                ZipOutputStream(FileOutputStream(z)).use { zo ->
                    us.forEachIndexed { k, u ->
                        var nome = "foto_${k + 1}.jpg"
                        contentResolver.query(u, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) nome = c.getString(0) }
                        zo.putNextEntry(ZipEntry(nome))
                        contentResolver.openInputStream(u)?.use { it.copyTo(zo) }
                        zo.closeEntry()
                    }
                }
                val uri = FileProvider.getUriForFile(this, "$packageName.arquivos", z)
                principal.post {
                    val i = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri).setType("application/zip")
                        .putExtra(Intent.EXTRA_TEXT, pasta).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(i, "Enviar $pasta.zip"))
                }
            } catch (e: Exception) {
                principal.post { Toast.makeText(this, "Erro no .zip: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    // ---------------------------------------------------------------- OS

    private fun acharOs(n: String): JSONObject? {
        val alvo = n.trim().trimStart('0')
        for (k in 0 until lista.length()) {
            val o = lista.optJSONObject(k) ?: continue
            if (o.optString("num").trimStart('0') == alvo) return o
        }
        return null
    }

    private fun distOs(o: JSONObject, l: Location): Double? =
        if (o.has("lat") && o.has("lon")) dist(l.latitude, l.longitude, o.getDouble("lat"), o.getDouble("lon")) else null

    /** Só sugere (não escolhe): a confirmação acontece na hora de salvar a foto. */
    private fun escolherOsPerto() {
        val l = local ?: return
        var melhor: JSONObject? = null; var md = RAIO_OS + l.accuracy.toDouble().coerceAtMost(MARGEM_GPS_MAX)
        for (k in 0 until lista.length()) {
            val o = lista.optJSONObject(k) ?: continue
            val d = distOs(o, l) ?: continue
            if (d < md) { md = d; melhor = o }
        }
        if (melhor?.optString("num") != sugestao?.optString("num")) { sugestao = melhor; mostrarOs() }
    }

    /** Saiu de perto da OS confirmada pelo GPS: a próxima foto volta a perguntar. */
    private fun soltarOsSeLonge() {
        val o = os ?: return
        val l = local ?: return
        if (!osPorGps) return
        val d = distOs(o, l) ?: return
        if (d > SOLTAR_OS && l.accuracy < MARGEM_GPS_MAX) {
            os = null; osManual = false; osPorGps = false; mostrarOs()
            Toast.makeText(this, "Saiu de perto da OS ${o.optString("num")}: a próxima foto vai perguntar a OS", Toast.LENGTH_LONG).show()
        }
    }

    private fun mostrarOs() {
        if (!::osTxt.isInitialized) return
        val o = os
        val sg = sugestao
        var cor = 0xFF005CA9.toInt()
        osTxt.text = when {
            o != null -> {
                val d = local?.let { distOs(o, it) }
                "OS ${o.optString("num")} · ${o.optString("bairro").ifBlank { o.optString("cidade") }}" + (if (osPorGps && d != null) " · ${metros(d)}" else "")
            }
            sg != null && !osManual -> {
                cor = 0xFFF39200.toInt()
                val d = local?.let { distOs(sg, it) }
                "OS ${sg.optString("num")}? · ${sg.optString("bairro").ifBlank { sg.optString("cidade") }}" + (d?.let { " · " + metros(it) } ?: "") + " · confirma na foto"
            }
            else -> { cor = 0xFF555555.toInt(); "Sem OS · toque para escolher" }
        }
        (osTxt.background as GradientDrawable).setColor(cor)
    }

    private fun escolherOsManual() {
        val l = local
        val itens = mutableListOf<Pair<String, JSONObject?>>()
        val ord = (0 until lista.length()).mapNotNull { lista.optJSONObject(it) }
            .sortedBy { o -> l?.let { distOs(o, it) } ?: Double.MAX_VALUE }
        for (o in ord) {
            val d = l?.let { distOs(o, it) }
            val dt = when { d == null -> ""; d < 1000 -> " · ${d.roundToInt()} m"; else -> " · ${String.format(Locale("pt", "BR"), "%.1f", d / 1000)} km" }
            itens.add("${o.optString("num")} · ${o.optString("cidade")} · ${o.optString("bairro")}$dt" to o)
        }
        itens.add("Digitar o número da OS" to JSONObject().put("_digitar", true))
        itens.add("Sem OS" to null)
        itens.add("Sugerir pelo GPS (confirma na foto)" to JSONObject().put("_auto", true))
        AlertDialog.Builder(this)
            .setTitle(if (lista.length() == 0) "Abra pelo app OS Caixa para ver a lista" else "Qual OS?")
            .setItems(itens.map { it.first }.toTypedArray()) { _, k ->
                val o = itens[k].second
                when {
                    o == null -> { os = null; osManual = true; osPorGps = false; mostrarOs() }
                    o.has("_auto") -> { osManual = false; osPorGps = false; os = null; sugestao = null; escolherOsPerto(); mostrarOs() }
                    o.has("_digitar") -> digitarOs { n -> if (n != null) { os = n; osManual = true; osPorGps = false; mostrarOs() } }
                    else -> { os = o; osManual = true; osPorGps = false; mostrarOs() }
                }
            }.show()
    }

    private fun digitarOs(fim: (JSONObject?) -> Unit) {
        val e = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "ex.: 001279844" }
        AlertDialog.Builder(this).setTitle("Número da OS").setView(e).setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                val n = e.text.toString().filter { it.isDigit() }
                fim(if (n.isNotEmpty()) acharOs(n) ?: JSONObject().put("num", n.padStart(9, '0')) else null)
            }.setNegativeButton("Cancelar") { _, _ -> fim(null) }.show()
    }

    // ---------------------------------------------------------------- GPS e endereço

    private fun dist(a: Double, b: Double, c: Double, d: Double): Double {
        val r = Math.PI / 180
        val x = sin((c - a) * r / 2).let { it * it } + cos(a * r) * cos(c * r) * sin((d - b) * r / 2).let { it * it }
        return 12742000 * asin(sqrt(x))
    }

    @SuppressLint("MissingPermission")
    private fun ligarGps() {
        if (gerente != null) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val g = getSystemService(LOCATION_SERVICE) as LocationManager
        gerente = g
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (g.isProviderEnabled(p)) {
                    g.getLastKnownLocation(p)?.let { if (local == null || it.time > local!!.time) local = it }
                    g.requestLocationUpdates(p, 1000L, 0f, ouvinte, Looper.getMainLooper())
                }
            } catch (_: Exception) {}
        }
        if (!osManual) escolherOsPerto()
        atualizarEndereco()
    }

    private fun desligarGps() {
        try { gerente?.removeUpdates(ouvinte) } catch (_: Exception) {}
        gerente = null
    }

    /** Bairro e cidade: da OS quando o ponto está perto dela; senão pelo Geocoder; senão o município mais próximo. */
    private fun textoLocal(l: Location?, o: JSONObject? = os): Pair<String, String>? {
        if (o != null && o.optString("cidade").isNotBlank()) {
            val d = l?.let { distOs(o, it) }
            if (d == null || d < 600) return o.optString("bairro") to o.optString("cidade")
        }
        return endereco
    }

    private fun atualizarEndereco() {
        val l = local ?: return
        if (buscandoEndereco) return
        val antes = enderecoEm
        if (antes != null && dist(antes.latitude, antes.longitude, l.latitude, l.longitude) < 60) return
        buscandoEndereco = true
        thread {
            var r: Pair<String, String>? = null
            try {
                if (Geocoder.isPresent()) {
                    @Suppress("DEPRECATION")
                    val a = Geocoder(this, Locale("pt", "BR")).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()
                    if (a != null) r = (a.subLocality ?: "") to (a.locality ?: a.subAdminArea ?: "")
                }
            } catch (_: Exception) {}
            if (r == null || r.second.isBlank()) r = (r?.first ?: "") to (Municipios.maisProximo(this, l.latitude, l.longitude) ?: "")
            principal.post { endereco = r; enderecoEm = l; buscandoEndereco = false }
        }
    }

    private val relogio = object : Runnable {
        override fun run() {
            val l = local
            carimboTxt.text = linhas(Date(), l, textoLocal(l), os).joinToString("\n")
            gpsTxt.text = when {
                l == null -> "Procurando GPS…"
                System.currentTimeMillis() - l.time > 120_000 -> "GPS antigo ±${l.accuracy.roundToInt()} m"
                else -> "GPS ±${l.accuracy.roundToInt()} m"
            }
            principal.postDelayed(this, 1000)
        }
    }
}

/** Municípios do Brasil (assets/mun.js) para achar a cidade sem internet. */
object Municipios {
    private var lista: List<Triple<String, Double, Double>>? = null
    fun maisProximo(ctx: android.content.Context, la: Double, lo: Double): String? {
        val l = lista ?: try {
            val s = ctx.assets.open("mun.js").bufferedReader().readText()
            val raw = s.substringAfter('"').substringBeforeLast('"')
            raw.split(';').mapNotNull { p -> val c = p.split('|'); if (c.size >= 4) Triple(c[0], c[2].toDouble(), c[3].toDouble()) else null }
        } catch (_: Exception) { emptyList() }
        lista = l
        return l.minByOrNull { (it.second - la) * (it.second - la) + (it.third - lo) * (it.third - lo) }?.first
    }
}
