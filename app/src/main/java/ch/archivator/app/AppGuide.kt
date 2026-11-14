package ch.archivator.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class GuideChapter(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val details: List<String>
)

private val archivatorChapters = listOf(
    GuideChapter(
        "Willkommen bei Archivator",
        "Dein digitales Dokumentenarchiv – privat und auf deinem Gerät.",
        Icons.Outlined.Inventory2,
        listOf(
            "Auf der Startseite siehst du wichtige Zahlen, letzte Dokumente und deine persönlichen Kacheln.",
            "Tippe auf eine Kachel, um den passenden Bereich zu öffnen. Die Kachel «Scan» zeigt Dokumente, die noch keiner bestimmten Kategorie zugeordnet sind.",
            "Zur Navigation dienen die Bereiche Übersicht, Archiv, Scannen, Fristen und Mehr. Die Zurück-Geste führt jeweils eine Ebene zurück."
        )
    ),
    GuideChapter(
        "Scannen und importieren",
        "Papier, Fotos und PDFs an einem Ort.",
        Icons.Outlined.DocumentScanner,
        listOf(
            "Starte «Jetzt scannen» oder «Scannen» unten. Du kannst mehrere Seiten erfassen, zuschneiden und als PDF speichern.",
            "Alternativ kannst du mit der Kamera fotografieren oder PDFs und Bilder aus dem Dateimanager importieren.",
            "Die Texterkennung (OCR) liest Dokumentinhalte. Passende Kategorien und Daten werden vorgeschlagen. Nicht zuordenbare Dokumente landen unter «Scan».",
            "Prüfe nach dem Import Titel, Kategorie, Betrag, Datum und Person und speichere die Änderungen. Der intelligente Scanner benötigt Google Play-Dienste."
        )
    ),
    GuideChapter(
        "Deine Startseite gestalten",
        "Kacheln anordnen, vergrössern und verkleinern.",
        Icons.Outlined.DashboardCustomize,
        listOf(
            "Ziehe zwei Finger auf der Startseite auseinander: Die Kacheln werden grösser und es erscheinen weniger pro Zeile.",
            "Schiebe zwei Finger zusammen: Die Kacheln werden kleiner. Möglich sind 2 bis 6 Kacheln nebeneinander.",
            "Halte eine Kachel lange gedrückt und ziehe sie an die gewünschte Position. Es sind keine zusätzlichen Griffpunkte nötig.",
            "Unter Einstellungen → «Startseite anpassen» kannst du Kacheln ein- oder ausblenden und ihre Reihenfolge auch mit Pfeilen ändern.",
            "Die gewählte Kachelgrösse und Reihenfolge bleiben beim nächsten Öffnen erhalten."
        )
    ),
    GuideChapter(
        "Dokumente finden und bearbeiten",
        "Suche, Vorschau und Fristen.",
        Icons.Outlined.FolderOpen,
        listOf(
            "Im Archiv kannst du Kategorien, Personen und Status auswählen und den lokal erkannten Dokumenttext durchsuchen.",
            "Öffne einen Eintrag, um Metadaten zu korrigieren, Schlagwörter zu ergänzen oder Dateien zu teilen.",
            "Die Vorschau unterstützt Zoom. Bei PDFs lassen sich Seiten drehen, sortieren, entfernen und einzeln exportieren; PDFs können auch zusammengefügt werden.",
            "Erkannte Dubletten werden gemeldet. Frühere PDF-Dateiversionen können, sofern vorhanden, wiederhergestellt werden.",
            "Für Rechnungen, Garantien und Verträge kannst du Termine pflegen und dich über Fristen erinnern lassen."
        )
    ),
    GuideChapter(
        "Haushalt und Finanzen",
        "Mehr als nur ein Dokumentenordner.",
        Icons.Outlined.AccountBalanceWallet,
        listOf(
            "Rechnungen verwalten Beträge, Zahlungsstatus, Fälligkeit, Wiederholungen und gegebenenfalls Schweizer QR-Zahlungsinformationen.",
            "Garantien zeigen ihre Laufzeit; Krankenkassenbelege erfassen Erstattungen und Bearbeitungsstatus.",
            "Familienordner ordnen Unterlagen Personen zu; Fahrzeug, Haushalt, Versicherungen und Abonnements bündeln passende Unterlagen.",
            "Finanzen bietet Übersichten und einen CSV-Export; die Zahlen beruhen auf erfassten Belegen und sind keine geprüften Bankumsätze.",
            "Steuern sammelt manuell markierte Belege nach Jahr. Zeitachse zeigt wichtige Daten. «Archivator fragen» durchsucht Dokumente mit lokalen Regeln und ist keine generative KI."
        )
    ),
    GuideChapter(
        "Schutz, Backups und Einstellungen",
        "Deine Daten bleiben unter deiner Kontrolle.",
        Icons.Outlined.Shield,
        listOf(
            "Dokumentdateien sind lokal mit AES-256-GCM verschlüsselt. Optional schützt eine biometrische oder gerätebasierte App-Sperre den Zugriff.",
            "Die App sperrt sich beim Verlassen wieder. Die Rückkehr aus einem gerade gestarteten Scan gehört zum selben Arbeitsvorgang und soll keine zweite Entsperrung verlangen.",
            "Du kannst passwortgeschützte Backups erstellen oder tägliche automatische Sicherungen in einem selbst gewählten Ordner einrichten. Bewahre das Passwort separat auf.",
            "Exportierte Finanz-CSV- und Steuer-ZIP-Dateien sind nicht durch das Archivator-Backup-Passwort verschlüsselt.",
            "Die lokale Datenbank mit Suchtexten und Metadaten ist nicht zusätzlich mit SQLCipher verschlüsselt; sie wird durch den privaten App-Speicher und die Gerätesicherheit geschützt.",
            "Es gibt keine automatische Synchronisierung zwischen Geräten. Unter Einstellungen findest du das dunkle Design, Sperre, Erinnerungen, Datensicherung und jederzeit wieder «App Funktion»."
        )
    )
)

@Composable
private fun GuideChapterCard(chapter: GuideChapter, number: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                    shape = RoundedCornerShape(13.dp)
                ) {
                    Icon(
                        chapter.icon, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(11.dp).size(25.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Kapitel $number", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(chapter.title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(chapter.subtitle, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            chapter.details.forEach { detail ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Text("•", color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold)
                    Text(detail, modifier = Modifier.weight(1f), fontSize = 13.sp,
                        lineHeight = 19.sp)
                }
            }
        }
    }
}

/** First-run onboarding and the complete, always-accessible settings help share one source. */
@Composable
fun ArchivatorGuide(firstLaunch: Boolean, onClose: () -> Unit) {
    var step by remember(firstLaunch) { mutableIntStateOf(0) }
    val chapters = archivatorChapters
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!firstLaunch) IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.ArrowBack, "Zurück")
                }
                Column(Modifier.weight(1f)) {
                    Text(if (firstLaunch) "Archivator kennenlernen" else "App Funktion",
                        fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(if (firstLaunch) "Deine Einführung" else "Alle Funktionen und die Bedienung",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (firstLaunch) TextButton(onClick = onClose) { Text("Überspringen") }
            }
        }
    ) { padding ->
        if (firstLaunch) {
            Column(
                Modifier.fillMaxSize().padding(padding)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                LinearProgressIndicator(
                    progress = { (step + 1).toFloat() / chapters.size },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Schritt ${step + 1} von ${chapters.size}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    GuideChapterCard(chapters[step], step + 1)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (step > 0) OutlinedButton(onClick = { step-- }) {
                        Text("Zurück")
                    }
                    Button(
                        onClick = { if (step == chapters.lastIndex) onClose() else step++ },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (step == chapters.lastIndex) "Archivator starten" else "Weiter")
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Outlined.ArrowForward, null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text("Hier findest du die vollständige Anleitung für die Startseite, das Scannen, die Archivfunktionen, die Sicherheit und die Datensicherung.",
                        fontSize = 14.sp, lineHeight = 21.sp)
                }
                items(chapters) { chapter ->
                    GuideChapterCard(chapter, chapters.indexOf(chapter) + 1)
                }
                item {
                    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                        Text("Zurück zu den Einstellungen")
                    }
                }
            }
        }
    }
}
