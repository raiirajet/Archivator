package ch.archivator.app

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.*
import org.junit.Test

class ArchiveImportDiagnosticsTest {
    @Test fun fileNotFoundExplainsPermissionAndRetry() {
        val problem = ImportFeedback.error(ImportStage.COPY, FileNotFoundException("private content://uri"))
        assertEquals("ARC-02-NOT_FOUND (FileNotFoundException)", problem.diagnosticCode)
        assertTrue(problem.description.contains("nicht lesen"))
        assertTrue(problem.remedy.contains("erneut"))
        assertFalse(problem.diagnosticCode.contains("content://"))
    }

    @Test fun emptyAndOversizedScansHaveDifferentMessages() {
        val empty = ImportFeedback.error(ImportStage.COPY, EmptyDocumentException())
        val large = ImportFeedback.error(ImportStage.COPY, FileTooLargeForArchiveException())
        assertTrue(empty.description.contains("leere Datei"))
        assertTrue(large.description.contains("100 MB"))
        assertNotEquals(empty.diagnosticCode, large.diagnosticCode)
    }

    @Test fun jpegIsRecognizedByBytesDespiteMisleadingPdfMime() {
        val file = File.createTempFile("archivator-image-", ".bin")
        try {
            file.writeBytes(byteArrayOf(
                0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(),
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
            ))
            assertEquals("image/jpeg", ArchiveEngine.detectDocumentMime(file, "application/pdf"))
        } finally { file.delete() }
    }

    @Test fun pdfAndPngAreRecognizedFromContent() {
        val file = File.createTempFile("archivator-doc-", ".bin")
        try {
            file.writeBytes("%PDF-1.7 test".toByteArray())
            assertEquals("application/pdf", ArchiveEngine.detectDocumentMime(file, null))
            file.writeBytes(byteArrayOf(
                0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
            ))
            assertEquals("image/png", ArchiveEngine.detectDocumentMime(file, "image/jpeg"))
        } finally { file.delete() }
    }

    @Test(expected = UnsupportedDocumentException::class)
    fun unrecognizedFileRejectedBeforeSavingBadPreview() {
        val file = File.createTempFile("archivator-unknown-", ".bin")
        try {
            file.writeBytes("Not a valid image or PDF".toByteArray())
            ArchiveEngine.detectDocumentMime(file, "application/pdf")
        } finally { file.delete() }
    }
}
