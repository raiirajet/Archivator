package ch.archivator.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run on an Android device/emulator: AndroidKeyStore is unavailable in local JVM tests. */
@RunWith(AndroidJUnit4::class)
class VaultEncryptionInstrumentedTest {
    @Test
    fun keystoreGeneratesFreshIvAndFilesDecrypt() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val original = File.createTempFile("archive-gcm-test-", ".pdf", ctx.cacheDir)
        val bytes = "%PDF-1.4 test content".toByteArray(Charsets.UTF_8)
        val encrypted = mutableListOf<File>()
        val decrypted = mutableListOf<File>()
        try {
            original.writeBytes(bytes)
            repeat(2) {
                val file = Vault.storeEncrypted(ctx, original)
                encrypted.add(file)
                val payload = file.readBytes()
                assertTrue(payload.size >= 4 + 12 + 16)
                assertArrayEquals(byteArrayOf(65, 86, 48, 50), payload.copyOfRange(0, 4))
                val doc = ArchiveDoc(file = file.name, mime = "application/pdf",
                    sha256 = ArchiveEngine.checksum(original))
                val output = Vault.plaintext(ctx, doc)
                decrypted.add(output)
                assertArrayEquals(bytes, output.readBytes())
            }
            val iv1 = encrypted[0].readBytes().copyOfRange(4, 16)
            val iv2 = encrypted[1].readBytes().copyOfRange(4, 16)
            assertFalse("Each document must use a unique GCM nonce", iv1.contentEquals(iv2))
        } finally {
            decrypted.forEach { it.delete() }
            encrypted.forEach { it.delete() }
            original.delete()
        }
    }
}
