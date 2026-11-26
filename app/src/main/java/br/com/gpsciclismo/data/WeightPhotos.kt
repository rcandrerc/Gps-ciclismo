package br.com.gpsciclismo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Fotos da balança: ficam em filesDir/weights/, já reduzidas (lado maior 1024 px, JPEG ~100 KB),
 * para não encher o tablet e caber num documento do Firestore.
 */
object WeightPhotos {
    private const val MAX_SIDE = 1024
    private const val QUALITY = 72

    fun dir(context: Context): File = File(context.filesDir, "weights").apply { mkdirs() }

    /** Arquivo temporário onde o app de câmera grava a foto. */
    fun captureFile(context: Context): File = File(dir(context), "captura.jpg")

    fun fileFor(context: Context, uuid: String): File = File(dir(context), "$uuid.jpg")

    /** Copia uma foto da galeria para o arquivo temporário. False se não deu para ler. */
    fun copyFromUri(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)!!.use { input ->
            FileOutputStream(captureFile(context)).use { input.copyTo(it) }
        }
        true
    }.getOrDefault(false)

    /** Reduz e endireita a foto temporária, gravando em [dest]. False se a foto não abriu. */
    fun compress(src: File, dest: File): Boolean = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val raw = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val rotation = when (ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val scale = MAX_SIDE.toFloat() / max(raw.width, raw.height)
        val m = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0f) postRotate(rotation)
        }
        val out = if (m.isIdentity) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        FileOutputStream(dest).use { out.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        if (out !== raw) out.recycle()
        raw.recycle()
        true
    }.getOrDefault(false)

    /** Abre a foto já reduzida para caber em [maxPx] (miniatura da lista ou tela cheia). */
    fun load(path: String?, maxPx: Int): Bitmap? {
        if (path == null || !File(path).exists()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
}
