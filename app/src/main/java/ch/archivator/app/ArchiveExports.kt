package ch.archivator.app

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exports are deliberately unencrypted; explicit UI warning before sending them elsewhere. */
object ArchiveExports {
    private fun date(d: ArchiveDoc): LocalDate {
        return runCatching { LocalDate.parse(d.purchased) }.getOrNull()
            ?: runCatching { LocalDate.parse(d.due) }.getOrNull()
            ?: Instant.ofEpochMilli(d.created).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    private fun csvField(value: String): String =
        "\"" + value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\""

    fun suggestedName(d: ArchiveDoc): String {
        val text = (d.merchant.ifBlank { d.title }).trim()
            .replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
            .trim('_').take(48).ifBlank { "Dokument" }
        val ext = when {
            d.mime.contains("pdf") -> "pdf"
            d.mime.contains("png") -> "png"
            d.mime.contains("webp") -> "webp"
            else -> "jpg"
        }
        val amount = d.amount.ifBlank { "ohne_Betrag" }.replace(Regex("[^0-9.,]"), "_")
        return "${date(d)}_${text}_${amount}_${d.id.take(7)}.$ext"
    }

    private fun csv(docs: List<ArchiveDoc>): String {
        val lines = mutableListOf(
            "Datum;Kategorie;Titel;Person;Betrag_CHF;Status;Haendler;IBAN;Referenz;Rechnungsnummer;Erstattet_CHF;Selbstbehalt_CHF;Steuerrelevant;Vorgang;Datei"
        )
        for (d in docs) {
            val row = listOf(date(d).toString(), d.category, d.title, d.person, d.amount,
                d.status, d.merchant, d.iban, d.reference, d.invoiceNumber, d.reimbursed,
                d.deductible, if (d.taxRelevant) "Ja" else "Nein", d.groupId,
                suggestedName(d))
            lines += row.joinToString(";") { csvField(it) }
        }
        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    fun financeCsv(ctx: Context, db: ArchiveDb, target: Uri, year: Int): Int {
        val docs = db.all().filter { date(it).year == year }
        ctx.contentResolver.openOutputStream(target, "w")?.use { stream ->
            // UTF-8 BOM ensures Excel handles Swiss/German umlauts correctly.
            stream.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            stream.write(csv(docs).toByteArray(Charsets.UTF_8))
        } ?: error("CSV-Ausgabedatei konnte nicht geöffnet werden")
        return docs.size
    }

    fun taxPackage(ctx: Context, db: ArchiveDb, target: Uri, year: Int): Int {
        val docs = db.all().filter {
            date(it).year == year && (it.taxRelevant ||
                it.category == "Steuern" || it.tags.contains("steuer", ignoreCase = true))
        }
        require(docs.size <= 2_000) { "Zu viele Dokumente für einen Export" }
        val output = ctx.contentResolver.openOutputStream(target, "w")
            ?: error("ZIP-Ausgabedatei konnte nicht geöffnet werden")
        output.use { stream ->
            ZipOutputStream(stream).use { zip ->
                zip.putNextEntry(ZipEntry("Steuerbelege_$year.csv"))
                zip.write(csv(docs).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                for (d in docs) {
                    val plain = Vault.plaintext(ctx, d)
                    try {
                        zip.putNextEntry(ZipEntry("Belege/" + suggestedName(d)))
                        plain.inputStream().use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    } finally {
                        if (Vault.isEncrypted(d)) plain.delete()
                    }
                }
            }
        }
        return docs.size
    }
}
