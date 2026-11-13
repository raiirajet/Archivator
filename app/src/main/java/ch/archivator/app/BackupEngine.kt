package ch.archivator.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ARC1 format: 4-byte magic, 16-byte random PBKDF2 salt, 12-byte random GCM IV,
 * followed by authenticated AES-256-GCM ciphertext wrapping a ZIP payload.
 * Passwords are never stored or logged.
 */
object BackupEngine {
    private val magic = byteArrayOf(65, 82, 67, 49)
    private const val maxArchive = 1024L * 1024L * 1024L
    private val safeName = Regex("[a-zA-Z0-9-]{1,80}\\.(pdf|jpg|jpeg|png|webp)")

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        require(password.length >= 8) { "Mindestens 8 Zeichen als Passwort verwenden" }
        val spec = PBEKeySpec(password.toCharArray(), salt, 210_000, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }

    fun export(ctx: Context, db: ArchiveDb, destination: Uri, password: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        }
        val docs = db.all()
        val exported = docs.associate { d ->
            val ext = when {
                d.mime.contains("pdf") -> ".pdf"
                d.mime.contains("png") -> ".png"
                d.mime.contains("webp") -> ".webp"
                else -> ".jpg"
            }
            d.id to (d.id + ext)
        }
        val array = JSONArray()
        docs.forEach { d ->
            val o = JSONObject()
            o.put("id", d.id); o.put("title", d.title); o.put("category", d.category)
            o.put("person", d.person); o.put("tags", d.tags); o.put("ocr", d.ocr)
            o.put("merchant", d.merchant); o.put("amount", d.amount); o.put("due", d.due)
            o.put("purchased", d.purchased); o.put("warrantyMonths", d.warrantyMonths)
            o.put("status", d.status); o.put("note", d.note); o.put("file", exported.getValue(d.id))
            o.put("mime", d.mime); o.put("sha256", d.sha256); o.put("created", d.created)
            o.put("iban", d.iban); o.put("reference", d.reference); o.put("invoiceNumber", d.invoiceNumber)
            o.put("recurringMonths", d.recurringMonths); o.put("reimbursed", d.reimbursed)
            o.put("deductible", d.deductible); o.put("qrPayload", d.qrPayload)
            o.put("groupId", d.groupId); o.put("taxRelevant", d.taxRelevant)
            o.put("cancellationDate", d.cancellationDate); o.put("policyNumber", d.policyNumber)
            o.put("assetName", d.assetName); o.put("paidDate", d.paidDate)
            o.put("reimbursedDate", d.reimbursedDate)
            array.put(o)
        }
        val output = ctx.contentResolver.openOutputStream(destination, "w")
            ?: error("Backup-Ziel kann nicht geöffnet werden")
        output.use { raw ->
            raw.write(magic); raw.write(salt); raw.write(iv)
            ZipOutputStream(CipherOutputStream(raw, cipher)).use { zip ->
                zip.putNextEntry(ZipEntry("docs.json"))
                zip.write(array.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                docs.forEach { d ->
                    val f = Vault.plaintext(ctx, d)
                    try {
                        zip.putNextEntry(ZipEntry("docs/" + exported.getValue(d.id)))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    } finally {
                        if (Vault.isEncrypted(d)) f.delete()
                    }
                }
            }
        }
    }

    fun restore(ctx: Context, db: ArchiveDb, source: Uri, password: String): Int {
        val staging = File(ctx.cacheDir, "restore-${System.nanoTime()}").apply { mkdirs() }
        try {
            val stream = ctx.contentResolver.openInputStream(source) ?: error("Backup kann nicht geöffnet werden")
            val json: String
            stream.use { raw ->
                val header = ByteArray(32)
                readFully(raw, header)
                require(header.copyOfRange(0, 4).contentEquals(magic)) { "Ungültige Archivator-Backupdatei" }
                val salt = header.copyOfRange(4, 20)
                val iv = header.copyOfRange(20, 32)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
                }
                // Decrypt the ENTIRE encrypted stream before accepting any ZIP data.
                // CipherInputStream must reach EOF to verify AES-GCM authentication.
                val verifiedZip = File(staging, "verified.zip")
                CipherInputStream(raw, cipher).use { plain ->
                    verifiedZip.outputStream().use { output ->
                        val buf = ByteArray(16_384)
                        var bytes = 0L
                        while (true) {
                            val n = plain.read(buf)
                            if (n < 0) break
                            bytes += n
                            require(bytes <= maxArchive) { "Backup zu gross" }
                            output.write(buf, 0, n)
                        }
                    }
                }
                var total = 0L
                var foundJson: String? = null
                ZipInputStream(verifiedZip.inputStream()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val n = entry.name
                        if (n == "docs.json") {
                            val bytes = zip.readBytesLimited(12 * 1024 * 1024)
                            total += bytes.size
                            foundJson = bytes.toString(Charsets.UTF_8)
                        } else if (n.startsWith("docs/") && safeName.matches(n.removePrefix("docs/"))) {
                            val output = File(staging, n.removePrefix("docs/"))
                            output.outputStream().use { dest ->
                                val buffer = ByteArray(16_384)
                                var size = 0L
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count == -1) break
                                    size += count
                                    total += count
                                    require(size <= 100 * 1024 * 1024L && total <= maxArchive) { "Backup zu gross" }
                                    dest.write(buffer, 0, count)
                                }
                            }
                        } else {
                            error("Unerwarteter Backup-Eintrag")
                        }
                        require(total <= maxArchive) { "Backup zu gross" }
                        zip.closeEntry()
                    }
                    // Payload was authenticated before ZIP parsing.
                }
                json = foundJson ?: error("Im Backup fehlen die Dokumentdaten")
            }
            val arr = JSONArray(json)
            require(arr.length() <= 20_000) { "Zu viele Dokumente im Backup" }
            var count = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val filename = o.optString("file")
                if (!safeName.matches(filename)) continue
                val staged = File(staging, filename)
                if (!staged.isFile) continue
                val hash = ArchiveEngine.checksum(staged)
                if (db.byHash(hash) != null) continue
                val newFile = Vault.storeEncrypted(ctx, staged)
                val d = ArchiveDoc(
                    title = o.optString("title", "Dokument"),
                    category = o.optString("category", "Sonstiges"),
                    person = o.optString("person"),
                    tags = o.optString("tags"),
                    ocr = o.optString("ocr"),
                    merchant = o.optString("merchant"),
                    amount = o.optString("amount"),
                    due = o.optString("due"),
                    purchased = o.optString("purchased"),
                    warrantyMonths = o.optInt("warrantyMonths"),
                    status = o.optString("status", "Offen"),
                    note = o.optString("note"),
                    file = newFile.name,
                    mime = o.optString("mime", "application/pdf"),
                    sha256 = hash,
                    created = o.optLong("created", System.currentTimeMillis()),
                    iban = o.optString("iban"),
                    reference = o.optString("reference"),
                    invoiceNumber = o.optString("invoiceNumber"),
                    recurringMonths = o.optInt("recurringMonths"),
                    reimbursed = o.optString("reimbursed"),
                    deductible = o.optString("deductible"),
                    qrPayload = o.optString("qrPayload"),
                    groupId = o.optString("groupId"),
                    taxRelevant = o.optBoolean("taxRelevant"),
                    cancellationDate = o.optString("cancellationDate"),
                    policyNumber = o.optString("policyNumber"),
                    assetName = o.optString("assetName"),
                    paidDate = o.optString("paidDate"),
                    reimbursedDate = o.optString("reimbursedDate")
                )
                try { db.put(d); count++ } catch (t: Exception) { newFile.delete(); throw t }
            }
            return count
        } finally { staging.deleteRecursively() }
    }

    private fun readFully(input: InputStream, dest: ByteArray) {
        var done = 0
        while (done < dest.size) {
            val n = input.read(dest, done, dest.size - done)
            require(n > 0) { "Unvollständige Backup-Datei" }
            done += n
        }
    }

    private fun InputStream.readBytesLimited(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            require(out.size() + n <= max) { "Dokumentenliste im Backup zu gross" }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
