package ch.archivator.app

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.security.KeyStore
import java.security.SecureRandom
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optional user-initiated backup into an SAF folder (e.g. local storage or a
 * cloud provider selected on-device). The backup password is encrypted using
 * a non-exportable Android Keystore key so a scheduled worker can use it.
 * The user MUST keep their original password separately for device recovery.
 */
object AutoBackups {
    private const val alias = "archivator-auto-backup-key"
    private const val job = "archivator-daily-backup"
    private fun preferences(ctx: Context) =
        ctx.getSharedPreferences("archivator-autobackup", Context.MODE_PRIVATE)
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun encrypt(password: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        }
        return Base64.encodeToString(iv + cipher.doFinal(password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(data: String): String {
        val bytes = Base64.decode(data, Base64.DEFAULT)
        require(bytes.size > 28) { "Ungültige Auto-Backup-Konfiguration" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }

    fun enabled(ctx: Context): Boolean =
        preferences(ctx).getString("folder", null) != null &&
        preferences(ctx).getString("password", null) != null

    fun lastResult(ctx: Context): String =
        preferences(ctx).getString("lastResult", "Noch keine automatische Sicherung") ?: ""

    fun configure(ctx: Context, folder: Uri, password: String) {
        require(password.length >= 8) { "Backup-Passwort muss mindestens 8 Zeichen enthalten" }
        ctx.contentResolver.takePersistableUriPermission(
            folder, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        preferences(ctx).edit().putString("folder", folder.toString())
            .putString("password", encrypt(password))
            .putString("lastResult", "Sicherung eingerichtet – nächste automatische Ausführung geplant")
            .apply()
        schedule(ctx)
    }

    fun schedule(ctx: Context) {
        if (!enabled(ctx)) return
        val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
        val work = PeriodicWorkRequestBuilder<AutoBackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            job, ExistingPeriodicWorkPolicy.UPDATE, work
        )
    }

    fun disable(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(job)
        preferences(ctx).edit().clear().apply()
    }

    internal fun execute(ctx: Context): Int {
        val uri = Uri.parse(preferences(ctx).getString("folder", null)
            ?: error("Kein Ordner eingerichtet"))
        val password = decrypt(preferences(ctx).getString("password", null)
            ?: error("Kein Passwort hinterlegt"))
        val tree = DocumentFile.fromTreeUri(ctx, uri) ?: error("Backup-Ordner nicht erreichbar")
        require(tree.canWrite()) { "Keine Schreibrechte im Sicherungsordner" }
        val filename = "Archivator-auto-${LocalDate.now()}-${System.currentTimeMillis()}.arc"
        val file = tree.createFile("application/octet-stream", filename)
            ?: error("Backup-Datei konnte nicht erstellt werden")
        try {
            ArchiveDb(ctx).use { db ->
                BackupEngine.export(ctx, db, file.uri, password)
                preferences(ctx).edit().putString("lastResult",
                    "Letzte Sicherung: ${LocalDate.now()} · ${db.all().size} Dokumente").apply()
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        // Retain the latest seven encrypted backups created by this app.
        tree.listFiles().filter { it.name?.startsWith("Archivator-auto-") == true &&
                it.name?.endsWith(".arc") == true }
            .sortedByDescending { it.name }.drop(7).forEach { it.delete() }
        return 1
    }

    internal fun failure(ctx: Context, message: String) {
        preferences(ctx).edit().putString("lastResult",
            "Letztes Backup fehlgeschlagen: " + message.take(100)).apply()
    }
}

class AutoBackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!AutoBackups.enabled(applicationContext)) return@withContext Result.success()
        runCatching { AutoBackups.execute(applicationContext) }
            .fold({ Result.success() }, {
                AutoBackups.failure(applicationContext, it.localizedMessage ?: "Zugriff verweigert")
                Result.retry()
            })
    }
}
