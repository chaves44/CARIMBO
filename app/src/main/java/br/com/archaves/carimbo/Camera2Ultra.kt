package br.com.archaves.carimbo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView

/**
 * Abre uma câmera direto pelo Camera2, pelo número (id), mesmo quando ela não aparece na lista
 * que o Android entrega aos apps. Na Xiaomi a lente 0,6 às vezes fica escondida assim.
 */
class Camera2Ultra(
    ctx: Context,
    private val id: String,
    private val textura: TextureView,
    private val erro: (String) -> Unit,
) {
    private val gerente = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val car = gerente.getCameraCharacteristics(id)
    private val orientacaoSensor = car.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    private var linha: HandlerThread? = null
    private var h: Handler? = null
    private var device: CameraDevice? = null
    private var sessao: CameraCaptureSession? = null
    private var leitor: ImageReader? = null
    private var superficie: Surface? = null

    private fun escolher(tams: Array<Size>, maxLado: Int): Size {
        val q = tams.filter { it.width * 3 == it.height * 4 }
        val bons = q.filter { it.width <= maxLado }
        return bons.maxByOrNull { it.width * it.height } ?: q.minByOrNull { it.width * it.height }
            ?: tams.maxByOrNull { it.width * it.height }!!
    }

    fun abrir() {
        val t = HandlerThread("camera2ultra").apply { start() }
        linha = t; h = Handler(t.looper)
        if (textura.isAvailable) iniciar()
        else textura.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, hh: Int) { iniciar() }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, hh: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun iniciar() {
        try {
            val mapa = car.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val foto = escolher(mapa.getOutputSizes(ImageFormat.JPEG), 3300)
            val prev = escolher(mapa.getOutputSizes(SurfaceTexture::class.java), 1600)
            leitor = ImageReader.newInstance(foto.width, foto.height, ImageFormat.JPEG, 2)
            textura.surfaceTexture!!.setDefaultBufferSize(prev.width, prev.height)
            superficie = Surface(textura.surfaceTexture)
            gerente.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; criarSessao() }
                override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
                override fun onError(d: CameraDevice, e: Int) { d.close(); device = null; erro("A lente $id recusou abrir (erro $e)") }
            }, h)
        } catch (e: Exception) { erro("Não abriu a lente $id: ${e.message}") }
    }

    @Suppress("DEPRECATION")
    private fun criarSessao() {
        val d = device ?: return
        val s = superficie ?: return
        val r = leitor ?: return
        try {
            d.createCaptureSession(listOf(s, r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(ss: CameraCaptureSession) {
                    sessao = ss
                    val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(s)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    }
                    try { ss.setRepeatingRequest(req.build(), null, h) } catch (e: Exception) { erro("Prévia da lente $id falhou: ${e.message}") }
                }
                override fun onConfigureFailed(ss: CameraCaptureSession) { erro("A lente $id não aceitou a configuração") }
            }, h)
        } catch (e: Exception) { erro("Sessão da lente $id falhou: ${e.message}") }
    }

    /** grausAparelho: 0, 90, 180 ou 270 (do OrientationEventListener). Devolve o JPEG e quanto girar. */
    fun fotografar(grausAparelho: Int, pronto: (ByteArray, Int) -> Unit) {
        val d = device; val ss = sessao; val r = leitor
        if (d == null || ss == null || r == null) { erro("A lente ainda não está pronta"); return }
        r.setOnImageAvailableListener({ rr ->
            val img = rr.acquireLatestImage() ?: return@setOnImageAvailableListener
            val b = img.planes[0].buffer
            val bytes = ByteArray(b.remaining()); b.get(bytes); img.close()
            pronto(bytes, (orientacaoSensor + grausAparelho) % 360)
        }, h)
        try {
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(r.surface)
                superficie?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_ORIENTATION, 0)
                set(CaptureRequest.JPEG_QUALITY, 92.toByte())
            }
            ss.capture(req.build(), null, h)
        } catch (e: Exception) { erro("Foto pela lente $id falhou: ${e.message}") }
    }

    fun fechar() {
        try { sessao?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { leitor?.close() } catch (_: Exception) {}
        try { superficie?.release() } catch (_: Exception) {}
        sessao = null; device = null; leitor = null; superficie = null
        textura.surfaceTextureListener = null
        linha?.quitSafely(); linha = null; h = null
    }
}
