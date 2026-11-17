# Archivator – digitaler Haushaltsmanager (Android)

Native Android-App in Kotlin/Jetpack Compose. Stand dieses Entwicklungszweigs: **2.5.3-beta4**.
Alle Kernfunktionen sind offline nutzbar; ein Konto oder eine Firebase-Anbindung ist nicht erforderlich.

## Neu seit 2.0

### Startseite: neue Kachelübersicht
- Kategorien als Kacheln in **zwei bis sechs Spalten**; mit zwei Fingern auseinanderziehen zum Vergrössern (weniger Spalten), zusammenziehen zum Verkleinern (mehr Spalten). Die Spaltenzahl bleibt gespeichert. Es gibt keine separaten 2-/3-Raster-Schalter mehr.
- **Langes Drücken und Ziehen** verschiebt Kacheln auf ein sichtbares Zielfeld. Die Griffpunkte mit sechs Punkten wurden entfernt; Verschieben funktioniert weiterhin an der ganzen Kachel. Die Reihenfolge wird sofort gespeichert, ergänzend gibt es in den Einstellungen Pfeiltasten.
- Die neue **«Scan»**-Kachel öffnet alle unzugeordneten Dokumente. Neue Dokumente, deren Kategorie nicht erkannt wird, werden unter **«Scan»** gespeichert. Vorhandene «Sonstiges»-Dokumente bleiben unverändert. Die neue Kachel wird bestehenden Installationen einmalig hinzugefügt.
- Die hellblaue Karte „Ein Scan. Alles im Griff.“ nutzt nun die vollständige verfügbare Displaybreite. Der Scan-Button ist kontrastreich weiss auf Dunkelblau.
- Kennzahlen und bisherige Funktionen bleiben erhalten; zuletzt archivierte Dokumente werden weiterhin angezeigt.

### Navigation und Erscheinungsbild
- Android-Zurück-Geste/-Taste schliesst **nicht** die App, sondern geht von Vorschau zu Dokument,
  zurück zum Bereich und erst dann zur Startseite; auf der Startseite wird nachgefragt.
- Babyblaues Kamera-Dokument-Icon, unterer Scan-Schnellzugriff und **tiefschwarzer Hintergrund** im Dunkel-Design.
- Eine **Einführung beim ersten Start** erklärt die Bedienung und alle wesentlichen Funktionen. Unter Einstellungen → **«App Funktion»** kann die komplette Anleitung jederzeit erneut geöffnet werden.
- Bei aktivierter App-Sperre löst die reguläre Rückkehr aus dem externen Dokumentenscanner keine sofortige zweite Entsperrung aus. Wenn der Scanner mehr als 30 Minuten offen ist, wird aus Sicherheitsgründen erneut gesperrt.
- Personalisierbare Startseite mit ein-/ausblendbaren und sortierbaren Kacheln.

### Archiv und Dokumente
- Dokumente per Smart-Scanner, Kamera oder PDF-/Bild-Dateiauswahl aufnehmen.
- Mehrere PDF-/Bild-Anhänge auch über `ACTION_SEND_MULTIPLE` aus anderen Apps importieren.
- OCR lokal mit ML Kit; für Rechnungen heuristische Kategorie, Händler, Datum, Betrag, Referenz.
- Kategorien: Rechnung, Quittung, Garantie, Krankenkasse, Versicherung, Vertrag, Fahrzeug,
  Wohnen, Abonnement, Bedienungsanleitung, Steuern, Scan (unzugeordnet), Sonstiges.
- Händlerbezogene Kategorie-Vorschläge merken sich nachträgliche Korrekturen.
- FTS4-Volltextindex für schnelle Offline-Suche, kombinierbar mit Person-, Kategorie- und Statusfiltern.
- SHA-256-Erkennung identischer Dateien, zusätzlich vorsichtige Warnung bei ähnlichen Dokumenten.
- Dokumente über eine gemeinsame Vorgangskennung verknüpfen.
- Exportdateinamen mit Datum, Händler, Betrag und kurzer Dokumentkennung.
- Vorschaubilder von Dokumenten im Archiv.

### PDF-Werkzeuge
- PDF- und Bildvorschau mit Zoom.
- PDF-Seiten in anderer Reihenfolge speichern, um 90 Grad drehen, entfernen oder als separates PDF exportieren.
- Vorhandene PDFs zusammenführen.
- PDFBox-Android dient zur nicht-rasterisierenden Seitenbearbeitung; PDF-Text- und
  Vektorinhalte bleiben soweit durch PDFBox unterstützt erhalten.
- Bis zu vier verschlüsselte frühere Dateiversionen werden lokal aufbewahrt, sofern die
  Datei über die PDF-Werkzeuge ersetzt wurde. Sie können wiederhergestellt werden.
- Digitale Signaturen können durch PDF-Bearbeitung ungültig werden.

### Haushaltsfunktionen
- Rechnungen: Status, IBAN/QR-IBAN, Referenz, Betrag, Fälligkeit, Wiederholung.
- Garantien: Kaufdatum, Dauer, Ablauf und Fristenhinweise.
- Krankenkassenbelege: Betrag, Erstattung, Selbstbehalt, Bearbeitungsstatus.
- Versicherungspolicen, Fahrzeug-/Haushaltsdokumente, Vertragsnummern und Kündigungsfristen.
- Finanz-Jahresübersicht mit Monatswerten und CSV-Export; Werte sind erfasste Belegbeträge,
  keine mit dem Bankkonto abgeglichenen echten Umsätze.
- Steuerordner: Dokumente manuell markieren, nach Jahr sammeln, als ZIP plus CSV exportieren.
- Chronologische Ereignisansicht für Dokumentdatum, Kauf, Fälligkeit und Garantie.
- „Archivator fragen“: lokale heuristische Dokumentfrage-Suche, **kein generatives KI-Modell**.

### Sicherheit
- **2.5.3-beta4:** Behebt «Caller-provided IV not permitted» beim Scannen,
  bei der Migration alter Dokumente und beim Einrichten automatischer Backups.
  Der Android Keystore erzeugt den eindeutigen GCM-IV selbst; vorhandene
  AV02-Dateien und verschlüsselte Auto-Backup-Passwortdaten bleiben lesbar.
  Die bisherigen Schlüssel-Aliase und Formate werden nicht geändert.
- Dokumentdateien AES-256-GCM-verschlüsselt im privaten App-Speicher mit Android-Keystore-Schlüssel.
- Geschützte Backups: portable passwortverschlüsselte ARC-Dateien mit PBKDF2 + AES-256-GCM.
- Automatische tägliche verschlüsselte Backups in einen vom Benutzer gewählten Android-Dokumentordner,
  optional über einen Cloud-Dateianbieter, der Androids Dateiauswahl unterstützt.
- Backup-Passwort wird nach ausdrücklicher Freigabe verschlüsselt durch Android Keystore
  geschützt auf dem Gerät hinterlegt; zur Wiederherstellung auf einem anderen Gerät
  **muss das Passwort separat aufbewahrt werden**.
- Biometrische bzw. gerätebasierte Entsperrung und Wiederverriegelung nach Verlassen der App.
- Sperrbildschirm-Benachrichtigungen zeigen keine vertraulichen Dokumenttitel.
- Keine Werbe-SDKs, keine Kontopflicht und keine automatische Netzwerkübertragung von Dokumenten.

**Datenschutzgrenze:** Die Dokumentdateien sind einzeln verschlüsselt. Die lokale
SQLite-Datenbank mit Metadaten und OCR-Suchindex ist **nicht separat SQLCipher-verschlüsselt**;
ihr Schutz beruht auf dem privaten Android-App-Speicher und der Gerätespeicherverschlüsselung.
Auch temporäre Klartextkopien im privaten Cache sind beim Teilen/Anzeigen erforderlich.
Sie werden bei einem App-Neustart entfernt.

**Exporthinweis:** Steuer-ZIP und Finanz-CSV sind im Klartext. Nur die
mit Passwort erzeugten ARC-Backup-Dateien sind zusätzlich verschlüsselt.

## APK bauen

Ein Push auf `feature/archivator-v3` startet GitHub Actions.
Der Workflow führt `gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` aus. Der Android-Instrumentierungstest ist erstellt und kompiliert, seine Ausführung auf einem echten Gerät/Emulator ist weiterhin erforderlich.
Nach einem erfolgreichen Lauf: **Actions → Android APK → Artifacts → Archivator-Android-APK**.

Debug-APKs sind nicht für die öffentliche Verteilung bestimmt und werden auf
unterschiedlichen GitHub-Runnern möglicherweise unterschiedlich signiert.
**Alte App-Version vor einem sicher geprüften Backup nicht deinstallieren.**

Für stabil signierte Releases bitte einen eigenen langfristig gesicherten
Keystore mit den vorhandenen Repository-Secrets
`ARCHIVATOR_KEYSTORE_B64`, `ARCHIVATOR_STORE_PASSWORD`,
`ARCHIVATOR_KEY_ALIAS`, `ARCHIVATOR_KEY_PASSWORD` konfigurieren.
Der Keystore darf nicht im Repository landen.

## Empfohlene Gerätetests vor Produktiveinsatz

1. Neuinstallation Android 8/12/15/16, Einführung, Darstellung in Hell und tiefem Schwarz.
2. Raster mit zwei Fingern auf 2, 3, 4, 5, 6 Spalten verändern; lange drücken und sortieren; «Scan» testen. Mit App-Sperre einen mehrseitigen Scan durchführen, ohne zweite Entsperrung.
3. Mehrfach zwischen Startseite, Archiv, Fristen und Einstellungen wechseln und
   per Zurück-Wischgeste jede Ebene einzeln verlassen; zu Hause Schliessdialog prüfen.
3. Mehrseitige PDFs, einzelne Bilder, Kamerafotos und Dateien aus Mail/Dateimanager importieren.
4. OCR-Ergebnisse korrigieren, anschliessend in der Volltextsuche wiederfinden.
5. Dieselbe Quittung zweimal scannen; Duplikathinweis kontrollieren.
6. Swiss-QR-Rechnung importieren: Betrag, QR-IBAN und Referenz manuell gegen Original abgleichen.
7. PDF-Seiten umsortieren, drehen, entfernen, zusammenführen und ältere Version wiederherstellen.
8. Garantie- und Zahlungsfristen mit Datum erfassen; Benachrichtigungen prüfen.
9. Verschlüsseltes Backup erstellen, Passwort absichtlich falsch und richtig testen;
   testweise auf einem **zweiten Gerät** wiederherstellen.
10. Automatisches Backup nur nach expliziter Auswahl eines beschreibbaren Ordners aktivieren.
11. Steuer-ZIP und Finanz-CSV auf korrekte Inhalte prüfen.
12. Vor jeder Aktualisierung mit bestehenden echten Daten zuerst ein verifiziertes Backup erstellen.

## Noch nicht als produktionsreif auszugeben

- Keine vollständige bidirektionale Cloud-Synchronisation oder geräteübergreifende
  Mehrbenutzer-Freigabe; Synchronisation erfolgt derzeit nur über verschlüsselte Sicherungen.
- Kein On-Device-LLM und keine generative KI; die lokale Frage-Suche nutzt Regeln.
- Keine direkte Bankanbindung, Zahlungsfreigabe oder vollständige E-Mail-Postfach-Synchronisation.
- Keine separate Verschlüsselung der SQL-Suchmetadaten.
- Automatische Klassifikation ist heuristisch und muss kontrolliert werden.
- GitHub CI testet Compiler und Unit-Tests, nicht Kamera, Biometrie und Gerätewechsel.

`main` und frühere Versionen bleiben unverändert, bis eine ausdrücklich gewünschte Freigabe erfolgt.
