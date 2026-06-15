package app.veyq.android.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import app.veyq.crypto.EmergencyKit
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.io.File
import java.io.FileOutputStream

/**
 * Build + share the Emergency Kit PDF on Android: human-readable rows + a QR encoding the cross-platform
 * `veyqkit:` token, so the Secret Key born during mobile registration can be re-imported by scanning it
 * on any device. Mirrors the web kit (downloadKitPdf).
 */
object EmergencyKitPdf {
    fun shareKit(context: Context, data: EmergencyKit.KitData, displayName: String) {
        val file = build(context, data, displayName)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, L10n.t("kit.download")))
    }

    private fun build(context: Context, data: EmergencyKit.KitData, displayName: String): File {
        val payload = EmergencyKit.payload(data)
        val qr = BarcodeEncoder().encodeBitmap(payload, BarcodeFormat.QR_CODE, 600, 600)

        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create()) // A4 @ 72dpi
        val c = page.canvas
        val title = Paint().apply { color = Color.rgb(20, 20, 20); textSize = 24f; isAntiAlias = true; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val muted = Paint().apply { color = Color.rgb(90, 90, 90); textSize = 12f; isAntiAlias = true }
        val key = Paint().apply { color = Color.rgb(20, 20, 20); textSize = 13f; isAntiAlias = true; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val value = Paint().apply { color = Color.rgb(20, 20, 20); textSize = 13f; isAntiAlias = true }
        val mono = Paint().apply { color = Color.rgb(20, 20, 20); textSize = 12f; isAntiAlias = true; typeface = Typeface.MONOSPACE }

        c.drawText("veyq — Emergency Kit", 56f, 70f, title)
        c.drawText("Keep this safe. With your Secret Key + your password you can always sign in again.", 56f, 100f, muted)

        var y = 150f
        for ((k, v) in listOf("Name" to displayName, "Instance" to (data.instance.ifBlank { "(same as the website)" }), "Email" to data.email)) {
            c.drawText(k, 56f, y, key); c.drawText(v, 170f, y, value); y += 28f
        }
        c.drawText("Secret Key", 56f, y, key)
        // wrap the secret across lines so it never runs off the page
        for (chunk in data.secretKey.chunked(48)) { c.drawText(chunk, 170f, y, mono); y += 18f }
        y += 24f

        c.drawBitmap(qr, null, android.graphics.RectF(56f, y, 206f, y + 150f), null)
        c.drawText("Scan this code on another device to fill in", 220f, y + 70f, muted)
        c.drawText("your email + Secret Key.", 220f, y + 88f, muted)
        doc.finishPage(page)

        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "veyq-emergency-kit-${data.email}.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }
}
