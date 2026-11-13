# Archivator – Dein digitaler Haushalt

Native Android-App (Kotlin / Jetpack Compose) für ein privates Haushaltsarchiv – grundsätzlich offline, ohne Benutzerkonto, ohne Firebase.

## Funktionen v1
- Mehrseitiger Dokumentenscan über Google ML Kit mit Zuschneiden, Drehen, Bildoptimierung und PDF
- Kamera-Fallback und Bild-/PDF-Import über Android-Dateiauswahl oder Teilen
- Offline-OCR mit ML Kit und nachträglicher Textkorrektur
- Dokumenttyp-Vorschlag, Betragserkennung und Fälligkeitsdatum-Vorschlag
- Archiv, Suche inklusive erkanntem Text, Tags und Filtern
- Rechnungen, Quittungen, Garantien, Krankenkassenbelege, Verträge, Steuern
- Rechnungstatus, Garantiedauer, Haushaltsmitglieder, Fristen mit Erinnerungen
- Duplikaterkennung (SHA-256), Öffnen, Teilen und Löschen
- Passphrase-verschlüsseltes Backup und Wiederherstellung; optionale biometrische Sperre

**Wichtig:** App-Dateien liegen im privaten Android-App-Verzeichnis und unterliegen der Gerätespeicherverschlüsselung. Eine zusätzliche dateiweise Verschlüsselung des lokalen Datenbestands ist in v1 nicht implementiert. Backup-Dateien werden hingegen mit PBKDF2-HMAC-SHA256 + AES-256-GCM verschlüsselt. Bei Verlust des Backup-Passworts ist keine Wiederherstellung möglich.

## APK erstellen
1. GitHub Actions > Android APK > Run workflow oder nach Push automatisch.
2. Erfolgreichen Workflow öffnen und unter "Artifacts" `Archivator-Android-APK` herunterladen.
3. ZIP entpacken und `app-debug.apk` auf Android 8+ installieren. Android fragt ggf. nach Erlaubnis für Installationen aus der Quelle.
4. Für produktive Verteilung einen eigenen Release-Keystore verwenden und eine signierte Release-Version bauen.

Lokaler Build: JDK 17 + Android SDK 35 + Gradle 8.10.2: `gradle :app:assembleDebug`.

### Datenschutz und Einschränkungen
Die Grundfunktionen nutzen keine Cloud. Der erweiterte ML-Kit-Scanner benötigt Google Play-Dienste und kann bei erster Verwendung zusätzliche Komponenten laden. Die Offline-OCR ist als lokale ML-Kit-Library eingebunden. Die Kamera-Fallback-Funktion nimmt ein Foto auf, bietet jedoch kein automatisches Zuschneiden. QR-Rechnungserkennung, robuste automatische strukturierte Belegerfassung, Cloud-Synchronisation und plattformübergreifende Familienfreigabe sind noch nicht integriert. Automatische Vorschläge sind heuristisch und müssen überprüft werden.

Kein Produktions-Release und kein Firebase-Deployment ohne Freigabe.
