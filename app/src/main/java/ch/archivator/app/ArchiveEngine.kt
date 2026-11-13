package ch.archivator.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

object ArchiveEngine {
    fun docsDir(ctx: Context): File = File(ctx.filesDir, "docs").apply { mkdirs() }

    suspend fun importDocument(ctx: Context, db: ArchiveDb, uri: Uri, suppliedMime: String? = null): Pair<ArchiveDoc?, String?> =
        withContext(Dispatchers.IO) {
            val mime = suppliedMime ?: ctx.contentResolver.getType(uri) ?: "application/pdf"
            val isPdf = mime.contains("pdf") || uri.toString().lowercase(Locale.ROOT).substringBefore("?").endsWith(".pdf")
            val ext = if (isPdf) "pdf" else "jpg"
            val f = File(docsDir(ctx), "${UUID.randomUUID()}.$ext")
            try {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    f.outputStream().use { input.copyTo(it) }
                } ?: throw IllegalStateException("Datei konnte nicht gelesen werden")
                if (f.length() == 0L) throw IllegalStateException("Leere Datei")
                if (f.length() > 100L * 1024 * 1024) throw IllegalArgumentException("Datei grösser als 100 MB")
                val hash = checksum(f)
                val existing = db.byHash(hash)
                if (existing != null) {
                    f.delete()
                    return@withContext Pair(existing, "Duplikat erkannt – vorhandenes Dokument geöffnet")
                }
                val text = try { recognize(ctx, f, isPdf) } catch (_: Exception) { "" }
                val guess = guessFromText(text)
                val d = ArchiveDoc(
                    title = guess.title,
                    category = guess.category,
                    merchant = guess.merchant,
                    amount = guess.amount,
                    due = guess.due,
                    ocr = text,
                    file = f.name,
                    mime = if (isPdf) "application/pdf" else "image/jpeg",
                    sha256 = hash
                )
                db.put(d)
                Pair(d, null)
            } catch (ex: Exception) {
                f.delete()
                Pair(null, ex.localizedMessage ?: "Import fehlgeschlagen")
            }
        }

    fun checksum(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { stream ->
            val b = ByteArray(16384)
            while (true) {
                val n = stream.read(b)
                if (n < 0) break
                md.update(b, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun recognize(ctx: Context, file: File, pdf: Boolean): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val texts = mutableListOf<String>()
            if (pdf) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        for (i in 0 until minOf(renderer.pageCount, 20)) {
                            renderer.openPage(i).use { page ->
                                val scale = minOf(2f, 1500f / maxOf(page.width, page.height))
                                val w = maxOf(1, (page.width * scale).toInt())
                                val h = maxOf(1, (page.height * scale).toInt())
                                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                try {
                                    b.eraseColor(android.graphics.Color.WHITE)
                                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    val text = Tasks.await(recognizer.process(InputImage.fromBitmap(b, 0))).text
                                    if (text.isNotBlank()) texts += text
                                } finally { b.recycle() }
                            }
                        }
                    }
                }
            } else {
                val img = InputImage.fromFilePath(ctx, Uri.fromFile(file))
                texts += Tasks.await(recognizer.process(img)).text
            }
            texts.joinToString("\n\n")
        } finally { recognizer.close() }
    }
}

data class TextGuess(val title: String, val category: String, val merchant: String, val amount: String, val due: String)

fun guessFromText(text: String): TextGuess {
    val lower = text.lowercase(Locale.ROOT)
    val category = when {
        Regex("krankenkasse|versicherer|leistungsabrechnung|ärzt|spital|tarmed|behandlung").containsMatchIn(lower) -> "Krankenkasse"
        Regex("garantie|gewährleistung|warranty").containsMatchIn(lower) -> "Garantie"
        Regex("steuererklärung|steueramt|steuerrechnung").containsMatchIn(lower) -> "Steuern"
        Regex("vertrag|vereinbarung|kündigungsfrist").containsMatchIn(lower) -> "Vertrag"
        Regex("rechnung|fällig|zahlung bis|einzahlungsschein|iban|qr-rechnung").containsMatchIn(lower) -> "Rechnung"
        Regex("quittung|kassenbon|kassenbeleg|retourenbon|beleg").containsMatchIn(lower) -> "Quittung"
        else -> "Sonstiges"
    }
    val merchant = text.lineSequence().map { it.trim() }.firstOrNull { it.length in 3..55 && it.any(Char::isLetter) } ?: ""
    val amountPattern = Regex("""(?i)(?:chf|fr[.]?|total|betrag)\s*[:=]?\s*([\d'’]+[.,]\d{2})""")
    val amount = amountPattern.findAll(text).lastOrNull()?.groupValues?.get(1)?.replace("'", "")?.replace("’", "")?.replace(",", ".") ?: ""
    val dueText = Regex("""(?i)(?:fällig(?:keit)?|zahlbar bis|zahlung bis|due date)\D{0,25}(\d{1,2})[./](\d{1,2})[./](\d{4})""").find(text)
    val due = dueText?.let {
        try {
            LocalDate.of(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt())
                .format(DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: Exception) { "" }
    } ?: ""
    val title = when {
        merchant.isBlank() -> category
        else -> "${category} · ${merchant.take(48)}"
    }
    return TextGuess(title, category, merchant, amount, due)
}
