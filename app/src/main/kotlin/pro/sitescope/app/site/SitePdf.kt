package pro.sitescope.app.site

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/** PDF-отчёт: тот же текст, что и в «Поделиться», на страницах A4 с переносом строк. */
object SitePdf {
    fun write(report: Report, out: OutputStream) {
        val doc = PdfDocument()
        val width = 595; val height = 842; val margin = 40f
        val body = Paint().apply { textSize = 11f; color = 0xFF1A2B33.toInt(); isAntiAlias = true }
        val title = Paint().apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD; color = 0xFF0B4651.toInt(); isAntiAlias = true }
        var number = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(width, height, number).create())
        var canvas = page.canvas
        var y = margin + 18f
        canvas.drawText("SiteScope Pro · ${report.host}", margin, y, title)
        y += 26f
        fun newPage() {
            doc.finishPage(page); number++
            page = doc.startPage(PdfDocument.PageInfo.Builder(width, height, number).create())
            canvas = page.canvas; y = margin + 12f
        }
        val lineHeight = 15f
        for (raw in SiteReportBuilder.toText(report).lines()) {
            var rest = raw
            if (rest.isEmpty()) { y += lineHeight / 2; continue }
            while (rest.isNotEmpty()) {
                val count = body.breakText(rest, true, width - margin * 2, null).coerceAtLeast(1)
                if (y > height - margin) newPage()
                canvas.drawText(rest.substring(0, count), margin, y, body)
                y += lineHeight
                rest = rest.substring(count).trimStart()
            }
        }
        doc.finishPage(page)
        try { doc.writeTo(out) } finally { doc.close() }
    }
}
