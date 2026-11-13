package ch.archivator.app

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.net.Uri
import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage
import java.io.File

data class SwissBill(
    val iban: String,
    val creditor: String,
    val amount: String,
    val currency: String,
    val reference: String,
    val message: String,
    val payload: String
)

/**
 * Swiss Payments Code according to SIX QR-bill data layout.
 * Never initiates a payment; financial fields are suggestions for user review.
 */
object SwissQr {
    fun parse(raw: String): SwissBill? {
        val fields = raw.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        if (fields.size < 31 || fields[0] != "SPC" || !fields[1].matches(Regex("0[23][0-9]{2}"))) return null
        if (fields[2] != "1") return null
        val iban = fields[3].replace(" ", "").uppercase()
        if (!iban.matches(Regex("(CH|LI)[0-9A-Z]{19}")) || !validIban(iban)) return null
        val currency = fields[19].uppercase()
        if (currency !in listOf("CHF", "EUR")) return null
        val amount = fields[18]
        if (amount.isNotBlank() && !amount.matches(Regex("[0-9]{1,10}\\.[0-9]{2}"))) return null
        val kind = fields[27]
        if (kind !in listOf("NON", "QRR", "SCOR")) return null
        val reference = fields[28]
        if (kind == "QRR" && !reference.matches(Regex("[0-9]{27}"))) return null
        if (kind == "SCOR" && !reference.matches(Regex("RF[0-9A-Z]{2,23}"))) return null
        if (kind == "NON" && reference.isNotBlank()) return null
        if (kind == "QRR" && !qrIban(iban)) return null
        if (kind != "QRR" && qrIban(iban)) return null
        return SwissBill(iban, fields[5], amount, currency, reference, fields[29], raw)
    }

    private fun qrIban(iban: String): Boolean {
        val iid = iban.substring(4, 9).toIntOrNull() ?: return false
        return iid in 30000..31999
    }

    private fun validIban(iban: String): Boolean {
        val reorder = iban.drop(4) + iban.take(4)
        var rem = 0
        for (char in reorder) {
            val str = if (char.isDigit()) char.toString() else (char.code - 'A'.code + 10).toString()
            for (digit in str) rem = (rem * 10 + (digit - '0')) % 97
        }
        return rem == 1
    }

    fun detect(ctx: Context, f: File, pdf: Boolean): SwissBill? {
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        fun analyze(img: InputImage): SwissBill? =
            Tasks.await(scanner.process(img)).asSequence().mapNotNull { parse(it.rawValue ?: "") }.firstOrNull()
        try {
            if (!pdf) return analyze(InputImage.fromFilePath(ctx, Uri.fromFile(f)))
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    for (i in 0 until minOf(renderer.pageCount, 5)) {
                        renderer.openPage(i).use { page ->
                            val scale = minOf(3f, 1800f / maxOf(page.width, page.height))
                            val bmp = Bitmap.createBitmap(
                                maxOf(1, (page.width * scale).toInt()),
                                maxOf(1, (page.height * scale).toInt()),
                                Bitmap.Config.ARGB_8888
                            )
                            try {
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                analyze(InputImage.fromBitmap(bmp, 0))?.let { return it }
                            } finally { bmp.recycle() }
                        }
                    }
                }
            }
            return null
        } finally { scanner.close() }
    }
}
