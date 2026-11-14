package ch.archivator.app

import org.junit.Assert.*
import org.junit.Test

class ArchiveHeuristicsTest {
    @Test fun recognizesInvoice() {
        val guess = guessFromText("Rechnung 1442\nFirma Muster AG\nTotal CHF 123.45\nzahlbar bis 24.12.2026")
        assertEquals("Rechnung", guess.category)
        assertEquals("123.45", guess.amount)
        assertEquals("2026-12-24", guess.due)
    }

    @Test fun uncategorizedTextIsSavedUnderScan() {
        assertEquals("Scan", guessFromText("Ideen und Notizen ohne Dokumenttyp").category)
        assertEquals("Scan", guessFromText("").category)
    }

    @Test fun recognizesInsurance() {
        val guess = guessFromText("Krankenkasse\nLeistungsabrechnung\nBetrag CHF 87.20")
        assertEquals("Krankenkasse", guess.category)
    }

    @Test fun invoiceNumberAndDate() {
        assertEquals("2026-10-12", guessPurchaseDate("Kaufdatum: 12.10.2026"))
        assertEquals("R-1123", guessInvoiceNumber("Rechnungsnummer: R-1123"))
    }

    @Test fun similarReceiptFlagsAnotherImageOfSamePurchase() {
        val first = ArchiveDoc(
            category = "Quittung", merchant = "Migros", amount = "39.90",
            purchased = "2026-11-13", invoiceNumber = "M-7843",
            ocr = "Migros Produkt A Produkt B Kasse Total 39.90"
        )
        val duplicate = similarDocument(listOf(first), "Migros", "39.90",
            "2026-11-13", "M-7843", "Migros Kasse Total")
        assertEquals(first.id, duplicate?.id)
    }

    @Test fun unrelatedInvoiceIsNotDuplicate() {
        val existing = ArchiveDoc(merchant = "Migros", amount = "12.90",
            purchased = "2026-11-10", invoiceNumber = "ABC3344")
        assertNull(similarDocument(listOf(existing), "Coop", "15.00",
            "2026-11-11", "XYZ1234", "anderer Händler"))
    }

    @Test fun swissQrValidatesIbanAndStructure() {
        val fields = MutableList(33) { "" }
        fields[0] = "SPC"; fields[1] = "0200"; fields[2] = "1"
        fields[3] = "CH9300762011623852957"; fields[4] = "S"
        fields[5] = "Muster AG"; fields[18] = "49.90"; fields[19] = "CHF"
        fields[27] = "NON"; fields[31] = "EPD"
        val bill = SwissQr.parse(fields.joinToString("\n"))
        assertNotNull(bill)
        assertEquals("CH9300762011623852957", bill!!.iban)
        assertEquals("49.90", bill.amount)
        fields[3] = "CH9300762011623852958"
        assertNull(SwissQr.parse(fields.joinToString("\n")))
    }
}
