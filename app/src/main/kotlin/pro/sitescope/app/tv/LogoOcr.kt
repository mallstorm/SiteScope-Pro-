package pro.sitescope.app.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * Распознавание названия канала по эмблеме (Tesseract, язык rus — кириллица, латиница и цифры).
 * Эмблема стоит в одном из углов кадра, поэтому читаются четыре угла каждого кадра; если уверенного
 * названия нет — результат пустой («нет названия»), догадки не выдаются.
 */
object LogoOcr {
    private const val ASSET = "tessdata/rus.traineddata"
    private val lock = Any()
    private var api: TessBaseAPI? = null
    private var failed = false

    private fun dataReady(context: Context): File? {
        val root = File(context.filesDir, "tesseract"); val dir = File(root, "tessdata"); val file = File(dir, "rus.traineddata")
        return try {
            val size = context.assets.openFd(ASSET).use { it.length }
            if (!file.exists() || file.length() != size) {
                dir.mkdirs()
                val tmp = File(dir, "rus.traineddata.part")
                context.assets.open(ASSET).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                if (!tmp.renameTo(file)) throw java.io.IOException("Не удалось сохранить языковые данные")
            }
            root
        } catch (e: Exception) {
            // Файл в assets может быть сжат и тогда openFd не работает: читаем потоком.
            try {
                dir.mkdirs()
                if (!file.exists() || file.length() < 1_000_000) {
                    val tmp = File(dir, "rus.traineddata.part")
                    context.assets.open(ASSET).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    if (!tmp.renameTo(file)) return null
                }
                root
            } catch (e2: Exception) { null }
        }
    }

    private fun engine(context: Context): TessBaseAPI? {
        api?.let { return it }
        if (failed) return null
        val root = dataReady(context) ?: run { failed = true; return null }
        val t = TessBaseAPI()
        return if (t.init(root.absolutePath, "rus")) {
            t.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
            api = t; t
        } else { t.recycle(); failed = true; null }
    }

    /** Углы кадра, где обычно стоит эмблема: левый верхний, правый верхний, правый нижний, левый нижний. */
    internal fun regions(w: Int, h: Int): List<Triple<String, Int, IntArray>> {
        val cw = (w * 0.30f).toInt().coerceAtLeast(8); val ch = (h * 0.22f).toInt().coerceAtLeast(8)
        val right = (w - cw).coerceAtLeast(0); val bottom = (h - ch).coerceAtLeast(0)
        return listOf(
            Triple("слева сверху", 0, intArrayOf(0, 0, cw, ch)),
            Triple("справа сверху", 1, intArrayOf(right, 0, cw, ch)),
            Triple("справа снизу", 2, intArrayOf(right, bottom, cw, ch)),
            Triple("слева снизу", 3, intArrayOf(0, bottom, cw, ch))
        )
    }

    private fun prepare(src: Bitmap, invert: Boolean): Bitmap {
        val targetW = 720
        val scale = targetW.toFloat() / src.width
        val scaled = Bitmap.createScaledBitmap(src, targetW, (src.height * scale).toInt().coerceAtLeast(8), true)
        val out = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val c = 1.7f                                             // контраст
        val base = ColorMatrix().apply { setSaturation(0f) }
        val contrast = ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, (-0.5f * c + 0.5f) * 255f, 0f, c, 0f, 0f, (-0.5f * c + 0.5f) * 255f, 0f, 0f, c, 0f, (-0.5f * c + 0.5f) * 255f, 0f, 0f, 0f, 1f, 0f))
        base.postConcat(contrast)
        if (invert) base.postConcat(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
        Canvas(out).drawBitmap(scaled, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(base) })
        if (scaled !== src) scaled.recycle()
        return out
    }

    private fun read(t: TessBaseAPI, bmp: Bitmap): Pair<String, Int> {
        t.setImage(bmp)
        val text = t.getUTF8Text() ?: ""
        return text to t.meanConfidence()
    }

    /** Читает углы всех кадров и выбирает название; пустая строка — название не видно. */
    fun recognize(context: Context, frames: List<Bitmap>): String {
      synchronized(lock) {
        val t = engine(context) ?: return ""
        val readings = ArrayList<LogoNamePicker.Reading>()
        for (frame in frames) {
            for ((name, _, r) in regions(frame.width, frame.height)) {
                val crop = try { Bitmap.createBitmap(frame, r[0], r[1], minOf(r[2], frame.width - r[0]), minOf(r[3], frame.height - r[1])) } catch (e: Exception) { continue }
                try {
                    var best: Pair<String, Int>? = null
                    for (invert in listOf(false, true)) {
                        val prepared = prepare(crop, invert)
                        try {
                            val got = read(t, prepared)
                            if (best == null || got.second > best.second) best = got
                        } finally { prepared.recycle() }
                        if ((best?.second ?: 0) >= 80) break
                    }
                    best?.let { readings.add(LogoNamePicker.Reading(it.first, it.second, name)) }
                } finally { crop.recycle() }
            }
            t.clear()
        }
        return LogoNamePicker.pickName(readings)
      }
    }
}
