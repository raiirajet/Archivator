package ch.archivator.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * File-at-rest encryption: AES-256-GCM, unique random nonce per document.
 * Key is held in Android Keystore. Plaintext is staged in private cache
 * for sharing and internal PDF operations, never in public storage.
 */
object Vault {
    private val magic = byteArrayOf(65, 86, 48, 50)
    private const val alias = "archivator-v2-file-key"
    private val store: KeyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun secret(): SecretKey {
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private fun validateFile(ctx: Context, name: String): File {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,120}")) && !name.contains(".."))
        return File(ArchiveEngine.docsDir(ctx), name)
    }

    fun storeEncrypted(ctx: Context, plain: File): File {
        val file = File(ArchiveEngine.docsDir(ctx), "${UUID.randomUUID()}.avc")
        try {
            // Android Keystore requires a provider-generated IV for randomized GCM encryption.
            // Keep the AV02 header format unchanged for existing documents and versions.
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, secret())
            }
            val iv = c.iv ?: error("Android Keystore hat keinen GCM-IV erzeugt")
            check(iv.size == 12) { "Unerwartete GCM-IV-Länge: ${iv.size}" }
            file.outputStream().use { output ->
                output.write(magic)
                output.write(iv)
                CipherOutputStream(output, c).use { enc ->
                    plain.inputStream().use { it.copyTo(enc) }
                }
            }
            return file
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    fun isEncrypted(doc: ArchiveDoc): Boolean = doc.file.endsWith(".avc")

    fun plaintext(ctx: Context, doc: ArchiveDoc): File {
        val original = validateFile(ctx, doc.file)
        require(original.isFile) { "Originaldatei fehlt" }
        if (!isEncrypted(doc)) return original
        val ext = when {
            doc.mime.contains("pdf") -> "pdf"
            doc.mime.contains("png") -> "png"
            doc.mime.contains("webp") -> "webp"
            else -> "jpg"
        }
        val targetDir = File(ctx.cacheDir, "decrypted").apply { mkdirs() }
        val temp = File(targetDir, "${UUID.randomUUID()}.$ext")
        try {
            original.inputStream().use { input ->
                val header = ByteArray(16)
                var pos = 0
                while (pos < header.size) {
                    val read = input.read(header, pos, header.size - pos)
                    require(read > 0) { "Verschlüsselte Datei beschädigt" }
                    pos += read
                }
                require(header.copyOfRange(0, 4).contentEquals(magic)) { "Ungültiges Dateiformat" }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, header.copyOfRange(4, 16)))
                }
                CipherInputStream(input, cipher).use { decrypted ->
                    temp.outputStream().use { decrypted.copyTo(it) }
                }
            }
            if (doc.sha256.isNotBlank()) require(ArchiveEngine.checksum(temp) == doc.sha256) { "Dateiintegrität konnte nicht bestätigt werden" }
            return temp
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    data class Version(val file: File, val timestamp: Long, val sha256: String)

    private fun versionDirectory(ctx: Context, id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        return File(ctx.filesDir, "versions/$id").apply { mkdirs() }
    }

    fun versions(ctx: Context, doc: ArchiveDoc): List<Version> =
        versionDirectory(ctx, doc.id).listFiles().orEmpty().mapNotNull { file ->
            val m = Regex("([0-9]{13})_([a-f0-9]{64})_[a-z0-9-]+\\.avc").matchEntire(file.name)
            if (m != null && file.isFile) Version(file, m.groupValues[1].toLong(), m.groupValues[2])
            else null
        }.sortedByDescending { it.timestamp }

    fun purgeVersions(ctx: Context, id: String) {
        versionDirectory(ctx, id).deleteRecursively()
    }

    private fun snapshot(ctx: Context, doc: ArchiveDoc) {
        val original = validateFile(ctx, doc.file)
        if (!original.isFile) return
        val copy = if (isEncrypted(doc)) original else storeEncrypted(ctx, original)
        try {
            val hash = if (doc.sha256.matches(Regex("[a-f0-9]{64}"))) doc.sha256
                else if (!isEncrypted(doc)) ArchiveEngine.checksum(original)
                else error("Datei ohne Integritätswert kann nicht versioniert werden")
            val versionName = "${System.currentTimeMillis()}_${hash}_${UUID.randomUUID()}.avc"
            val target = File(versionDirectory(ctx, doc.id), versionName)
            copy.copyTo(target, overwrite = false)
            versions(ctx, doc).drop(4).forEach { it.file.delete() }
        } finally {
            if (!isEncrypted(doc)) copy.delete()
        }
    }

    fun restoreVersion(ctx: Context, db: ArchiveDb, doc: ArchiveDoc, version: Version) {
        require(version in versions(ctx, doc)) { "Version nicht vorhanden" }
        val temp = File(ArchiveEngine.docsDir(ctx), "${UUID.randomUUID()}.avc")
        try {
            version.file.copyTo(temp)
            val candidate = doc.copy(file = temp.name, sha256 = version.sha256)
            val plain = plaintext(ctx, candidate)
            try {
                replace(ctx, db, doc, plain)
            } finally { plain.delete() }
        } finally { temp.delete() }
    }

    fun shareCopy(ctx: Context, doc: ArchiveDoc): File {
        val plain = plaintext(ctx, doc)
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        val safe = ArchiveExports.suggestedName(doc)
        val dest = File(dir, safe)
        try {
            plain.copyTo(dest, overwrite = true)
            return dest
        } finally { if (isEncrypted(doc)) plain.delete() }
    }

    fun replace(ctx: Context, db: ArchiveDb, doc: ArchiveDoc, replacementPlain: File) {
        val encrypted = storeEncrypted(ctx, replacementPlain)
        val old = validateFile(ctx, doc.file)
        try {
            snapshot(ctx, doc)
            db.put(doc.copy(file = encrypted.name, sha256 = ArchiveEngine.checksum(replacementPlain)))
            old.delete()
        } catch (e: Exception) {
            encrypted.delete()
            throw e
        }
    }

    fun migrate(ctx: Context, db: ArchiveDb): Int {
        var count = 0
        for (doc in db.all().filterNot(::isEncrypted)) {
            val plain = validateFile(ctx, doc.file)
            if (!plain.isFile) continue
            val encrypted = storeEncrypted(ctx, plain)
            try {
                db.put(doc.copy(file = encrypted.name))
                plain.delete()
                count++
            } catch (e: Exception) {
                encrypted.delete()
                throw e
            }
        }
        return count
    }

    fun purgeCache(ctx: Context) {
        File(ctx.cacheDir, "decrypted").listFiles()?.forEach { it.delete() }
        File(ctx.cacheDir, "shared").listFiles()?.forEach { it.delete() }
    }
}
