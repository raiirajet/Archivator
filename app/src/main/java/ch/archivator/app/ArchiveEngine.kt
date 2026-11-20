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

    /**
     * Always save the original encrypted file first. OCR, QR extraction and classification
     * are optional enhancements: none may prevent a readable document from being archived.
     */
    suspend fun importDocument(
        ctx: Context,
        db: ArchiveDb,
        uri: Uri,
        suppliedMime: String? = null
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val scratch = File(ctx.cacheDir, "archive-import/${UUID.randomUUID()}.bin")
        var stage = ImportStage.OPEN
        var encrypted: File? = null
        var savedToDatabase = false
        val warnings = mutableListOf<String>()
        try {
            scratch.parentFile?.mkdirs()
            stage = ImportStage.COPY
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                scratch.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val length = input.read(buffer)
                        if (length == -1) break
                        total += length
                        if (total > 100L * 1024 * 1024) throw FileTooLargeForArchiveException()
                        output.write(buffer, 0, length)
                    }
                    if (total == 0L) throw EmptyDocumentException()
                }
            } ?: throw java.io.FileNotFoundException("Kein Datenstrom für den Scan")
            stage = ImportStage.FORMAT
            val mime = detectDocumentMime(scratch, suppliedMime ?: ctx.contentResolver.getType(uri))
            val isPdf = mime == "application/pdf"
            val hash = checksum(scratch)

            // A repeat scan of the same receipt should open the existing file instead
            // of creating duplicates. Repair references to missing encrypted files.
            val existing = db.byHash(hash)
            if (existing != null) {
                val onDisk = File(docsDir(ctx), existing.file)
                if (onDisk.isFile && onDisk.length() > 32L) {
                    return@withContext ImportOutcome.Saved(existing, duplicate = true)
                }
                warnings += "Ein vorhandener Eintrag hatte keine nutzbare Datei. Die Originaldatei wurde erneut gespeichert."
            }

            stage = ImportStage.ENCRYPT
            val encryptedFile = Vault.storeEncrypted(ctx, scratch)
            encrypted = encryptedFile
            check(encryptedFile.isFile && encryptedFile.length() > 32L) {
                "Verschlüsselte Datei konnte nicht geschrieben werden"
            }

            stage = ImportStage.DATABASE
            val initial = if (existing != null) {
                existing.copy(file = encryptedFile.name, mime = mime, sha256 = hash)
            } else {
                ArchiveDoc(
                    title = "Scan vom " + java.time.LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")),
                    category = "Scan",
                    file = encryptedFile.name,
                    mime = mime,
                    sha256 = hash
                )
            }
            val indexed = db.put(initial)
            savedToDatabase = true
            if (!indexed) warnings += "Gespeichert, aber der Suchindex konnte nicht aktualisiert werden."

            stage = ImportStage.VERIFY
            val verified = db.byId(initial.id)
            check(verified != null && verified.file == initial.file &&
                encryptedFile.isFile && encryptedFile.length() > 32L) {
                "Archivierung nicht bestätigt"
            }
            // Once committed, no metadata failure may delete the encrypted original.
            if (existing != null) {
                return@withContext ImportOutcome.Saved(verified, warnings = warnings)
            }

            var detectedText = ""
            try {
                detectedText = recognize(ctx, scratch, isPdf)
            } catch (_: Exception) {
                warnings += "Das Dokument ist gespeichert, die Texterkennung war jedoch nicht verfügbar."
            }

            try {
                val guess = guessFromText(detectedText)
                val swiss = try { SwissQr.detect(ctx, scratch, isPdf) }
                    catch (_: Exception) { null }
                val predicted = if (swiss != null) "Rechnung"
                    else CategoryMemory.prediction(ctx, guess.merchant) ?: guess.category
                val merchant = swiss?.creditor?.ifBlank { guess.merchant } ?: guess.merchant
                val amount = swiss?.amount?.ifBlank { guess.amount } ?: guess.amount
                val matching = similarDocument(
                    db.all().filterNot { it.id == initial.id },
                    guess.merchant, amount, guessPurchaseDate(detectedText),
                    guessInvoiceNumber(detectedText), detectedText
                )
                val updated = verified.copy(
                    title = if (swiss != null) "QR-Rechnung · " + swiss.creditor else
                        if (guess.category == "Scan" && guess.merchant.isBlank()) verified.title
                        else guess.title,
                    category = predicted,
                    merchant = merchant,
                    amount = amount,
                    due = guess.due,
                    ocr = detectedText,
                    iban = swiss?.iban ?: "",
                    reference = swiss?.reference ?: "",
                    qrPayload = swiss?.payload ?: "",
                    invoiceNumber = guessInvoiceNumber(detectedText),
                    purchased = guessPurchaseDate(detectedText)
                )
                val searchUpdated = db.put(updated)
                if (!searchUpdated && indexed) {
                    warnings += "Die Daten wurden gespeichert, aber die Volltextsuche ist vorübergehend eingeschränkt."
                }
                if (matching != null) warnings +=
                    "Möglicher Doppelbeleg zu: ${matching.title}. Bitte prüfen."
                return@withContext ImportOutcome.Saved(updated, warnings = warnings)
            } catch (_: Exception) {
                warnings += "Das Dokument ist gesichert, aber die automatische Auswertung ist fehlgeschlagen. Angaben können manuell ergänzt werden."
                return@withContext ImportOutcome.Saved(verified, warnings = warnings)
            }
        } catch (error: Exception) {
            if (!savedToDatabase) encrypted?.delete()
            ImportOutcome.Failed(ImportFeedback.error(stage, error))
        } finally {
            scratch.delete()
        }
    }

    /** Examine file bytes, not just MIME declarations (which are often missing for shared photos). */
    internal fun detectDocumentMime(file: File, declared: String?): String {
        val header = ByteArray(16)
        val size = file.inputStream().use { it.read(header) }
        if (size < 4) throw UnsupportedDocumentException()
        val ascii = String(header, Charsets.ISO_8859_1)
        return when {
            ascii.startsWith("%PDF-") -> "application/pdf"
            (header[0].toInt() and 0xff) == 0xff &&
                (header[1].toInt() and 0xff) == 0xd8 &&
                (header[2].toInt() and 0xff) == 0xff -> "image/jpeg"
            (header[0].toInt() and 0xff) == 0x89 && ascii.substring(1).startsWith("PNG") -> "image/png"
            ascii.startsWith("RIFF") && ascii.substring(8, 12) == "WEBP" -> "image/webp"
            ascii.substring(4, 8) == "ftyp" &&
                listOf("heic", "heix", "mif1", "hevc").any { ascii.substring(8, 12) == it } -> "image/heic"
            else -> throw UnsupportedDocumentException()
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
        }?.takeIf { it in categories && it != "Sonstiges" && it != "Alle" && it != "Scan" }

    fun remember(ctx: Context, doc: ArchiveDoc) {
        val id = key(doc.merchant) ?: return
        if (doc.category !in categories || doc.category in listOf("Alle", "Sonstiges", "Scan")) return
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
