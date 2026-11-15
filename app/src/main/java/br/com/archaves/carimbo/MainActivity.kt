package br.com.archaves.carimbo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Base64
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var web: WebView
    private val fotosDir by lazy { File(filesDir, "fotos").apply { mkdirs() } }
    private val principal = Handler(Looper.getMainLooper())

    private var arquivoCamera: File? = null
    private var capturando = false
    private var melhorLocal: Location? = null
    private var gerente: LocationManager? = null
    private var ouvindo = false

    private val ouvinte = LocationListener { l -> if (melhor(l, melhorLocal)) melhorLocal = l }

    companion object {
        const val REQ_CAMERA = 11
        const val REQ_GALERIA = 12
        const val REQ_PERM = 21
        const val ORIGEM = "https://appassets.androidplatform.net"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString("arquivoCamera")?.let { arquivoCamera = File(it) }
        capturando = savedInstanceState?.getBoolean("capturando") ?: false

        web = WebView(this)
        setContentView(web)

        val carregador = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/fotos/", WebViewAssetLoader.InternalStoragePathHandler(this, fotosDir))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setGeolocationEnabled(true)
            allowFileAccess = false
            allowContentAccess = false
            userAgentString = "$userAgentString CarimboFotos/1.0 (andre2087 at gmail.com)"
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                carregador.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, true, false)
            }
        }
        web.addJavascriptInterface(Ponte(), "Android")
        web.loadUrl("$ORIGEM/assets/index.html")

        pedirPermissoes()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        arquivoCamera?.let { outState.putString("arquivoCamera", it.absolutePath) }
        outState.putBoolean("capturando", capturando)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.voltar && window.voltar()") { r ->
            if (r != "true") finish()
        }
    }

    // ---------------- permissões e GPS ----------------

    private fun pedirPermissoes() {
        val lista = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) lista.add(Manifest.permission.READ_MEDIA_IMAGES)
        else lista.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 29) lista.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        if (Build.VERSION.SDK_INT <= 28) lista.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val faltam = lista.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (faltam.isNotEmpty()) requestPermissions(faltam.toTypedArray(), REQ_PERM) else ligarGps()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ligarGps()
        avisarJs("{\"tipo\":\"permissoes\"}")
    }

    private fun temGps() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun ligarGps() {
        if (ouvindo || !temGps()) return
        val g = getSystemService(LOCATION_SERVICE) as LocationManager
        gerente = g
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (g.isProviderEnabled(p)) {
                    g.getLastKnownLocation(p)?.let { if (melhor(it, melhorLocal)) melhorLocal = it }
                    g.requestLocationUpdates(p, 2000L, 0f, ouvinte, Looper.getMainLooper())
                }
            } catch (_: Exception) {}
        }
        ouvindo = true
    }

    private fun desligarGps() {
        try { gerente?.removeUpdates(ouvinte) } catch (_: Exception) {}
        ouvindo = false
    }

    override fun onResume() { super.onResume(); ligarGps() }
    override fun onPause() { super.onPause(); if (!capturando) desligarGps() }
    override fun onDestroy() { desligarGps(); super.onDestroy() }

    private fun melhor(novo: Location, atual: Location?): Boolean {
        if (atual == null) return true
        val dt = novo.time - atual.time
        if (dt > 60_000) return true
        if (dt < -60_000) return false
        val dp = novo.accuracy - atual.accuracy
        return dp < 0 || (dt > 0 && dp <= 20)
    }

    private fun localJson(): JSONObject? {
        val l = melhorLocal ?: return null
        val idade = System.currentTimeMillis() - l.time
        return JSONObject().apply {
            put("lat", l.latitude); put("lon", l.longitude)
            if (l.hasAltitude()) put("alt", l.altitude)
            put("prec", l.accuracy.toDouble())
            put("idade", idade)
        }
    }

    // ---------------- câmera e galeria ----------------

    private fun abrirCamera() {
        val f = File(fotosDir, "cam_${System.currentTimeMillis()}.jpg")
        arquivoCamera = f
        val uri = FileProvider.getUriForFile(this, "$packageName.arquivos", f)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        capturando = true
        ligarGps()
        try { startActivityForResult(i, REQ_CAMERA) } catch (e: ActivityNotFoundException) {
            capturando = false
            avisarJs("{\"tipo\":\"erro\",\"msg\":\"Nenhum app de câmera encontrado.\"}")
        }
    }

    private fun abrirGaleria() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try { startActivityForResult(i, REQ_GALERIA) } catch (e: Exception) {
            val j = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }
            startActivityForResult(j, REQ_GALERIA)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAMERA) {
            capturando = false
            val f = arquivoCamera
            if (resultCode == RESULT_OK && f != null && f.exists() && f.length() > 0) {
                val item = JSONObject().apply {
                    put("arquivo", f.name); put("nome", f.name); put("origem", "camera")
                    put("quando", System.currentTimeMillis())
                    localJson()?.let { put("local", it) }
                }
                avisarJs(JSONObject().put("tipo", "novas").put("arquivos", JSONArray().put(item)).toString())
            } else {
                f?.delete()
            }
            arquivoCamera = null
        } else if (requestCode == REQ_GALERIA && resultCode == RESULT_OK && data != null) {
            val uris = mutableListOf<Uri>()
            data.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
            if (uris.isEmpty()) return
            avisarJs("{\"tipo\":\"lendo\",\"n\":${uris.size}}")
            thread {
                val arr = JSONArray()
                uris.forEachIndexed { k, u ->
                    try {
                        val f = File(fotosDir, "gal_${System.currentTimeMillis()}_$k.jpg")
                        copiarOriginal(u, f)
                        val (nome, mod) = infoDocumento(u)
                        arr.put(JSONObject().apply {
                            put("arquivo", f.name); put("nome", nome ?: f.name); put("origem", "galeria")
                            put("quando", mod ?: f.lastModified())
                        })
                    } catch (_: Exception) {}
                }
                avisarJs(JSONObject().put("tipo", "novas").put("arquivos", arr).toString())
            }
        }
    }

    /** Copia a foto pedindo o arquivo original (com GPS), quando o Android deixa. */
    private fun copiarOriginal(uri: Uri, destino: File) {
        var fonte = uri
        if (Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                val media = if (uri.authority == MediaStore.AUTHORITY) uri else MediaStore.getMediaUri(this, uri)
                if (media != null) fonte = MediaStore.setRequireOriginal(media)
            } catch (_: Exception) {}
        }
        val entrada = try { contentResolver.openInputStream(fonte) } catch (_: Exception) { null }
            ?: contentResolver.openInputStream(uri) ?: throw IllegalStateException("sem acesso")
        entrada.use { i -> FileOutputStream(destino).use { o -> i.copyTo(o) } }
    }

    private fun infoDocumento(uri: Uri): Pair<String?, Long?> {
        var nome: String? = null
        var mod: Long? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (n >= 0) nome = c.getString(n)
                    val m = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (m >= 0 && !c.isNull(m)) mod = c.getLong(m)
                }
            }
        } catch (_: Exception) {}
        return Pair(nome, mod)
    }

    private fun avisarJs(json: String) {
        principal.post {
            val txt = JSONObject.quote(json)
            web.evaluateJavascript("window.doAndroid && window.doAndroid($txt)", null)
        }
    }

    // ---------------- ponte com a tela ----------------

    inner class Ponte {
        @JavascriptInterface fun tirarFoto() { principal.post { abrirCamera() } }
        @JavascriptInterface fun escolherFotos() { principal.post { abrirGaleria() } }
        @JavascriptInterface fun localizacao(): String = localJson()?.toString() ?: ""
        @JavascriptInterface fun gpsPermitido(): Boolean = temGps()
        @JavascriptInterface fun pedirPermissoes() { principal.post { this@MainActivity.pedirPermissoes() } }

        /** Grava a foto carimbada na galeria (Imagens/Carimbo). Se já existe, substitui. Devolve o endereço. */
        @JavascriptInterface
        fun salvarImagem(base64: String, nome: String, existente: String): String {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            if (existente.isNotEmpty()) {
                try {
                    val u = Uri.parse(existente)
                    contentResolver.openOutputStream(u, "wt")!!.use { it.write(bytes) }
                    return existente
                } catch (_: Exception) { /* foi apagada ou não é mais nossa: grava nova */ }
            }
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, nome)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Carimbo")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val u = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return ""
            contentResolver.openOutputStream(u)!!.use { it.write(bytes) }
            if (Build.VERSION.SDK_INT >= 29) {
                cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(u, cv, null, null)
            }
            return u.toString()
        }

        @JavascriptInterface
        fun compartilhar(lista: String) {
            val arr = JSONArray(lista)
            val uris = ArrayList<Uri>()
            for (k in 0 until arr.length()) uris.add(Uri.parse(arr.getString(k)))
            if (uris.isEmpty()) return
            val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            i.type = "image/jpeg"
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            principal.post { startActivity(Intent.createChooser(i, "Enviar fotos")) }
        }

        @JavascriptInterface
        fun apagarOriginal(arquivo: String) {
            val f = File(fotosDir, File(arquivo).name)
            if (f.exists()) f.delete()
        }

        @JavascriptInterface
        fun abrirLink(url: String) {
            principal.post { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {} }
        }
    }
}
