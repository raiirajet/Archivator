package ch.archivator.app

import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteException
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException

/** Safe, actionable errors: never include scanned text, filenames or provider URIs. */
enum class ImportStage(val code: String, val label: String) {
    OPEN("01", "Datei öffnen"),
    COPY("02", "Datei einlesen"),
    FORMAT("03", "Dateiformat prüfen"),
    ENCRYPT("04", "Datei verschlüsseln"),
    DATABASE("05", "Im Archiv speichern"),
    VERIFY("06", "Speicherung überprüfen"),
    SCANNER("07", "Scanner-Ergebnis übernehmen")
}

sealed class ImportOutcome {
    data class Saved(
        val document: ArchiveDoc,
        val duplicate: Boolean = false,
        val warnings: List<String> = emptyList()
    ) : ImportOutcome()

    data class Failed(val error: ImportProblem) : ImportOutcome()
}

data class ImportProblem(
    val stage: ImportStage,
    val heading: String,
    val description: String,
    val remedy: String,
    val diagnosticCode: String
)

class UnsupportedDocumentException : IllegalArgumentException("Unbekanntes PDF- oder Bildformat")
class FileTooLargeForArchiveException : IOException("Datei grösser als 100 MB")
class EmptyDocumentException : IOException("Dokument enthält keine Daten")

object ImportFeedback {
    fun error(stage: ImportStage, cause: Throwable): ImportProblem {
        val type = cause.javaClass.simpleName.ifBlank { "Unbekannt" }
        val code = "ARC-${stage.code}-${when (cause) {
            is SecurityException -> "PERMISSION"
            is FileNotFoundException -> "NOT_FOUND"
            is FileTooLargeForArchiveException -> "TOO_LARGE"
            is EmptyDocumentException -> "EMPTY"
            is UnsupportedDocumentException -> "FORMAT"
            is SQLiteFullException -> "DB_FULL"
            is SQLiteDatabaseLockedException -> "DB_BUSY"
            is SQLiteException -> "DATABASE"
            is GeneralSecurityException -> "CRYPTO"
            is IOException -> "IO"
            else -> type.uppercase().take(24)
        }}"
        val (description, remedy) = when {
            cause is SecurityException || cause is FileNotFoundException ->
                "Archivator kann die ausgewählte Aufnahme nicht lesen. Die Datei ist möglicherweise nicht mehr verfügbar oder die Freigabe ist abgelaufen." to
                    "Öffne den Scan oder die Bilddatei erneut und erlaube den Zugriff. Ist es ein Foto aus einer anderen App, wähle es über «PDF oder Bild importieren»."
            cause is FileTooLargeForArchiveException ->
                "Die Datei überschreitet die erlaubte Grösse von 100 MB." to
                    "Teile das PDF in kleinere Dateien oder reduziere die Bildgrösse und versuche es erneut."
            cause is EmptyDocumentException ->
                "Der Scanner beziehungsweise die Kamera hat eine leere Datei übergeben." to
                    "Scanne das Dokument erneut. Prüfe, ob die Seitenvorschau vor dem Bestätigen sichtbar ist."
            cause is UnsupportedDocumentException ->
                "Das übergebene Format konnte nicht als PDF oder unterstütztes Bild erkannt werden." to
                    "Versuche, das Dokument als PDF, JPG, PNG oder WebP erneut zu speichern und danach zu importieren."
            cause is SQLiteFullException || cause.message?.contains("database or disk is full", ignoreCase = true) == true ->
                "Auf dem Gerät ist möglicherweise nicht genügend Speicherplatz für das Archiv vorhanden." to
                    "Gib Speicherplatz frei und versuche den Import erneut. Vorhandene Dokumente bleiben erhalten."
            cause is SQLiteDatabaseLockedException ->
                "Die Archivdatenbank ist gerade durch einen anderen Vorgang belegt." to
                    "Warte, bis ein laufender Import oder eine Sicherung abgeschlossen ist, und versuche es danach nochmals."
            cause is SQLiteException ->
                "Die Datei konnte nicht in die lokale Archivdatenbank eingetragen werden." to
                    "Starte Archivator neu und versuche den Import erneut. Besteht der Fehler weiter, notiere den Fehlercode; lösche die App nicht."
            stage == ImportStage.ENCRYPT || cause is GeneralSecurityException ->
                "Archivator konnte die Datei nicht mit dem Geräteschlüssel verschlüsseln." to
                    "Entsperre das Gerät und starte Archivator neu. Besteht der Fehler weiter, notiere den Fehlercode. Deinstalliere die App nicht."
            stage == ImportStage.VERIFY ->
                "Die Speicherung konnte nicht zuverlässig bestätigt werden." to
                    "Prüfe das Archiv auf den neuen Eintrag. Importiere erst erneut, wenn er dort nicht vorhanden ist."
            stage == ImportStage.SCANNER ->
                "Der Scanner hat kein verwendbares Dokument übergeben." to
                    "Starte den Scanner erneut oder wähle unter «Scannen» die Kamera beziehungsweise den Dateiimport."
            else ->
                "Beim Lesen oder Speichern der Datei ist ein technischer Fehler aufgetreten." to
                    "Versuche es erneut. Tritt der Fehler wiederholt auf, notiere den Fehlercode und die Aktion, bei der er erscheint."
        }
        return ImportProblem(
            stage = stage,
            heading = "Dokument nicht gespeichert",
            description = description,
            remedy = remedy,
            diagnosticCode = "$code ($type)"
        )
    }
}
