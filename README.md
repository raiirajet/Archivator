# Archivator 2.0 – dein digitaler Haushalt

Native Android-App in Kotlin / Jetpack Compose, offline-first. Kein Konto und keine Firebase-Anbindung notwendig.

## Funktionen

- Modernes babyblaues Dashboard mit Schnellscan, Rechnungen, Krankenkasse und Garantiefristen.
- Google-ML-Kit-Dokumentenscanner mit mehrseitigen PDFs, automatischem Zuschnitt und Kamera-Fallback.
- Lokale OCR, automatische heuristische Dokumentkategorie, Betrag, Händler, Rechnungsnummer, Datum und Fälligkeit.
- Schweizer QR-Rechnungen: QR-Code offline erkennen, QR-IBAN/IBAN, Referenz und Betrag übernehmen. **Kein Zahlungsauftrag** – Daten vor Nutzung kontrollieren.
- Volldurchsuchbares Archiv mit Mehrwort-Suche über OCR, Namen, Beträge, IBAN, Referenz, Notizen und Tags; Filter nach Typ, Status und Haushaltsmitglied.
- Rechnungen: Offen/Bezahlt, wiederkehrende Fälligkeit (in Monaten) und Erinnerungen.
- Garantien: Kaufdatum, Dauer, berechnetes Ende und Hinweis vor Ablauf.
- Krankenkassen-Cockpit: Belegstatus, Erstattungsbeträge, Selbstbehalt/Franchise.
- PDF-/Bildvorschau; PDF-Seiten drehen und umsortieren. **Achtung:** Beim PDF-Speichern werden die Seiten als Bilder neu erzeugt. Ursprüngliche Vektor-/Textschichten können verloren gehen; vorher Backup erstellen.
- Verschlüsselte Dokumentdateien (Android Keystore / AES-256-GCM) und passwortgeschützte Backups (AES-256-GCM / PBKDF2-HMAC-SHA256). Bestehende v1-Dateien werden nach der Aktualisierung in-place migriert.
- Biometrische App-Sperre, lokale Datenbank, kein Tracking.
- Datei-Import aus Android-Dateiauswahl und Share-Menü.

## Datenschutz

Dokumentdateien liegen nur verschlüsselt in der privaten App-Ablage. Dateischlüssel sind im lokalen Android Keystore gespeichert. Metadaten und OCR-Texte liegen weiterhin in einer lokalen SQLite-Datei; sie profitieren von der Gerätespeicherverschlüsselung, sind aber **nicht separat feldweise verschlüsselt**. PDF-Vorschau und Teilen erfordern temporäre Klartextdateien im privaten Cache, der beim nächsten App-Start geleert wird. Ein entsperrtes oder kompromittiertes Gerät kann diese Inhalte dennoch offenlegen.

Backups sind über ein eigenes Passwort verschlüsselt. Für eine Wiederherstellung auf einem anderen Gerät das Backup UND das Passwort aufbewahren; der Android-Keystore-Schlüssel allein lässt sich nicht exportieren. Die Originaldateien werden beim Migrationsschritt erst nach erfolgreicher Verschlüsselung und Datenbankaktualisierung gelöscht. Trotzdem vor Updates ein Backup sichern.

OCR und KI-Vorschläge sind heuristisch und können falsch sein. Die QR-Daten entsprechen einem strukturierten Swiss Payments Code, sind aber keine Bankprüfung und dürfen nicht blind übernommen werden. Weder E-Mail-Import noch echte Bankzahlungen sind integriert.

## Debug-APK über GitHub Actions

1. Reiter **Actions** > **Android APK** auf den Entwicklungszweig wechseln.
2. Erfolgreichen Build öffnen > **Artifacts** > **Archivator-Android-APK** herunterladen.
3. ZIP entpacken und `app-debug.apk` auf Android installieren.

**Achtung:** GitHub-Runner erzeugen Debug-Builds eventuell mit unterschiedlichen Signaturschlüsseln. Ein Debug-APK muss nicht über ältere Debug-Installationen installierbar sein. Alte App **nicht deinstallieren, bevor die Wiederherstellbarkeit eines verschlüsselten Backups geprüft ist**.

## Dauerhaft signierte Releases vorbereiten

Damit Android APK-Updates später dieselbe Signatur besitzen, muss **ein dauerhaft gesicherter privater Keystore** verwendet werden. Die CI kann optional `app-release.apk` bauen, wenn folgende Repository Secrets gesetzt sind:

- `ARCHIVATOR_KEYSTORE_B64`: Base64-kodierter Inhalt des privaten Release-Keystores
- `ARCHIVATOR_STORE_PASSWORD`: Store-Passwort
- `ARCHIVATOR_KEY_ALIAS`: Alias
- `ARCHIVATOR_KEY_PASSWORD`: Schlüssel-Passwort

Der Keystore wird nicht im Repository eingecheckt. Sein Verlust verhindert spätere updates mit derselben Paketkennung. Beispiel für die Erstellung auf einem vertrauenswürdigen eigenen Rechner:

```bash
keytool -genkeypair -v -keystore archivator-release.jks -alias archivator -keyalg RSA -keysize 3072 -validity 10000
base64 -w0 archivator-release.jks
```

Füge den Base64-Wert als erstes Secret hinzu und sichere die Keystore-Datei und Passwörter ausserhalb von GitHub an zwei geschützten Orten. Bei konfigurierten Secrets erstellt der Workflow zusätzlich das Artefakt `Archivator-SIGNED-RELEASE-APK`. Die Veröffentlichung eines Releases erfolgt nicht automatisch. Eine Debug-Installation kann wegen unterschiedlicher Signatur weiterhin nicht auf diese Release-Installation wechseln; sichere vorher deine Daten.

## Tech

JDK 17, Android SDK 35, Gradle 8.10.2, Android Gradle Plugin 8.7.2, Kotlin 2.1.21, Jetpack Compose, WorkManager, Android Keystore und Google ML Kit. Lokal mit `gradle :app:testDebugUnitTest :app:assembleDebug` bauen.

### Offene Grenzen

Die Garantie- und Rechnungsfristen sind lokale Erinnerungen, keine verbindlichen Zahlungstermine. Der Wiederholungsturnus verwaltet anstehende Perioden; keine automatische Bankzahlung. Familienordner sind lokal – keine geräteübergreifende Familienfreigabe. Die QR-Decoderlogik erkennt strukturierte QR-Rechnungen in Bildern und den ersten fünf PDF-Seiten. Bei schlechten Scans kann die Erkennung versagen. Für eine produktive Veröffentlichung sind zusätzliche Geräte-, Sicherheits- und Migrationstests nötig.
