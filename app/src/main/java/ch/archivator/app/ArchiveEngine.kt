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
            val ext = when {
                isPdf -> "pdf"
                mime.contains("png") -> "png"
                mime.contains("webp") -> "webp"
                else -> "jpg"
            }
            val f = File(ctx.cacheDir, "${UUID.randomUUID()}.$ext")
            var encrypted: File? = null
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
                val swiss = try { SwissQr.detect(ctx, f, isPdf) } catch (_: Exception) { null }
                val predictedCategory = if (swiss != null) "Rechnung"
                    else CategoryMemory.prediction(ctx, guess.merchant) ?: guess.category
                val matching = similarDocument(db.all(), guess.merchant,
                    swiss?.amount?.ifBlank { guess.amount } ?: guess.amount,
                    guessPurchaseDate(text), guessInvoiceNumber(text), text)
                encrypted = Vault.storeEncrypted(ctx, f)
                val d = ArchiveDoc(
                    title = if (swiss != null) "QR-Rechnung · " + swiss.creditor else guess.title,
                    category = predictedCategory,
                    merchant = swiss?.creditor?.ifBlank { guess.merchant } ?: guess.merchant,
                    amount = swiss?.amount?.ifBlank { guess.amount } ?: guess.amount,
                    due = guess.due,
                    ocr = text,
                    file = encrypted.name,
                    mime = if (isPdf) "application/pdf" else when (ext) {
                        "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg"
                    },
                    sha256 = hash,
                    iban = swiss?.iban ?: "",
                    reference = swiss?.reference ?: "",
                    qrPayload = swiss?.payload ?: "",
                    invoiceNumber = guessInvoiceNumber(text),
                    purchased = guessPurchaseDate(text)
                )
                db.put(d)
                Pair(d, matching?.let { "Möglicher Doppelbeleg zu: ${it.title}. Bitte prüfen." })
            } catch (ex: Exception) {
                encrypted?.delete()
                Pair(null, ex.localizedMessage ?: "Import fehlgeschlagen")
            } finally {
                f.delete()
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
        Regex("police|privathaftpflicht|hausratversicherung|versicherungsprämie").containsMatchIn(lower) -> "Versicherung"
        Regex("serviceheft|fahrzeugausweis|reifenwechsel|fahrzeugprüfung|kilometerstand").containsMatchIn(lower) -> "Fahrzeug"
        Regex("mietvertrag|nebenkostenabrechnung|vermieter|hausverwaltung").containsMatchIn(lower) -> "Wohnen"
        Regex("abonnement|abo-rechnung|monatliche gebühr|abo-gebühr").containsMatchIn(lower) -> "Abonnement"
        Regex("bedienungsanleitung|gebrauchsanweisung|user manual").containsMatchIn(lower) -> "Bedienungsanleitung"
        Regex("steuererklärung|steueramt|steuerrechnung").containsMatchIn(lower) -> "Steuern"
        Regex("vertrag|vereinbarung|kündigungsfrist").containsMatchIn(lower) -> "Vertrag"
        Regex("rechnung|fällig|zahlung bis|einzahlungsschein|iban|qr-rechnung").containsMatchIn(lower) -> "Rechnung"
        Regex("quittung|kassenbon|kassenbeleg|retourenbon|beleg").containsMatchIn(lower) -> "Quittung"
        else -> "Scan"
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

fun guessInvoiceNumber(text: String): String =
    Regex("""(?i)(?:rechnungs(?:nummer|nr\.?|nr)|invoice\s*(?:no\.?|number)|beleg\s*nr\.?)\s*[:#-]?\s*([A-Z0-9\-/.]{3,35})""")
        .find(text)?.groupValues?.getOrNull(1) ?: ""

fun guessPurchaseDate(text: String): String {
    val candidate = Regex("""(?i)(?:kaufdatum|belegdatum|datum|date)\s*[:#-]?\s*(\d{1,2})[./](\d{1,2})[./](\d{4})""")
        .find(text) ?: return ""
    return runCatching {
        LocalDate.of(candidate.groupValues[3].toInt(), candidate.groupValues[2].toInt(),
            candidate.groupValues[1].toInt()).toString()
    }.getOrDefault("")
}

object CategoryMemory {
    private fun key(merchant: String): String? {
        val normalized = merchant.trim().lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), "_").take(60)
        return normalized.takeIf { it.length >= 3 }
    }
    fun prediction(ctx: Context, merchant: String): String? =
        key(merchant)?.let {
            ctx.getSharedPreferences("archive-category-memory", Context.MODE_PRIVATE)
                .getString(it, null)
        }?.takeIf { it in categories && it != "Sonstiges" && it != "Alle" }

    fun remember(ctx: Context, doc: ArchiveDoc) {
        val id = key(doc.merchant) ?: return
        if (doc.category !in categories || doc.category in listOf("Alle", "Sonstiges")) return
        ctx.getSharedPreferences("archive-category-memory", Context.MODE_PRIVATE)
            .edit().putString(id, doc.category).apply()
    }
}

fun similarDocument(
    existing: List<ArchiveDoc>, merchant: String, amount: String,
    purchaseDate: String, invoiceNumber: String, text: String
): ArchiveDoc? {
    fun norm(s: String) = s.lowercase(java.util.Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]"), "")
    val seller = norm(merchant)
    val invoice = norm(invoiceNumber)
    val words = text.lowercase(java.util.Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length > 4 }.toSet()
    return existing.firstOrNull { doc ->
        val sameInvoice = invoice.length >= 5 && invoice == norm(doc.invoiceNumber) &&
            seller.length >= 3 && seller == norm(doc.merchant)
        val sameReceipt = seller.length >= 3 && seller == norm(doc.merchant) &&
            amount.isNotBlank() && amount == doc.amount &&
            purchaseDate.isNotBlank() && purchaseDate == doc.purchased
        val oldWords = doc.ocr.lowercase(java.util.Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 4 }.toSet()
        val overlap = if (words.size >= 8 && oldWords.size >= 8)
            words.intersect(oldWords).size.toDouble() / words.union(oldWords).size
            else 0.0
        sameInvoice || sameReceipt || overlap >= 0.90
    }
}
