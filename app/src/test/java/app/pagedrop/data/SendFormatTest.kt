package app.pagedrop.data

import app.pagedrop.data.SendFormatSetting.KEEP_ORIGINAL
import app.pagedrop.data.SendFormatSetting.CONVERT
import app.pagedrop.data.SendPlan.ConvertOnPhoneMobi
import app.pagedrop.data.SendPlan.KeepOriginal
import app.pagedrop.ui.book.AddPlan
import app.pagedrop.ui.book.addPlanFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendFormatTest {
    private fun decide(
        format: String,
        setting: SendFormatSetting = CONVERT,
        keep: Boolean? = null,
        server: String? = null,
        convertOnly: Boolean = false,
    ) = SendFormatDecision.decide(format, setting, keep, server, convertOnly)

    // ── EPUB ──

    @Test fun epubFollowsTheSettingWhenThereIsNoOverride() {
        assertEquals(ConvertOnPhoneMobi, decide("EPUB", CONVERT))
        assertEquals(KeepOriginal, decide("EPUB", KEEP_ORIGINAL))
    }

    @Test fun epubOverrideBeatsTheSetting() {
        assertEquals(KeepOriginal, decide("EPUB", CONVERT, keep = true))
        assertEquals(ConvertOnPhoneMobi, decide("EPUB", KEEP_ORIGINAL, keep = false))
    }

    @Test fun epubConvertGoesToTheServerWhenItIsOn() {
        assertEquals(SendPlan.Server("azw3"), decide("EPUB", CONVERT, server = "AZW3"))
        assertEquals(SendPlan.Server("mobi"), decide("epub", KEEP_ORIGINAL, keep = false, server = "MOBI"))
    }

    @Test fun epubKeepNeverUsesTheServerEvenWhenItIsOn() {
        assertEquals(KeepOriginal, decide("EPUB", KEEP_ORIGINAL, server = "MOBI"))
        assertEquals(KeepOriginal, decide("EPUB", CONVERT, keep = true, server = "MOBI"))
    }

    @Test fun convertOnlyAlwaysConvertsEvenWithKeepEpub() {
        assertEquals(ConvertOnPhoneMobi, decide("EPUB", KEEP_ORIGINAL, keep = true, convertOnly = true))
        assertEquals(SendPlan.Server("mobi"), decide("EPUB", KEEP_ORIGINAL, server = "mobi", convertOnly = true))
    }

    // ── Other formats are unchanged ──

    @Test fun pdfFollowsTheSettingLikeEveryOtherFormat() {
        assertEquals(ConvertOnPhoneMobi, decide("PDF", CONVERT))
        assertEquals(KeepOriginal, decide("PDF", KEEP_ORIGINAL))
        assertEquals(KeepOriginal, decide("PDF", CONVERT, keep = true))
        assertEquals(ConvertOnPhoneMobi, decide("PDF", KEEP_ORIGINAL, keep = false))
        assertEquals(SendPlan.Server("mobi"), decide("PDF", CONVERT, keep = false, server = "MOBI"))
    }

    @Test fun kindleReadyFormatsAreAlwaysAsIs() {
        listOf("MOBI", "AZW3", "AZW", "PRC", "mobi").forEach { f ->
            assertEquals(f, KeepOriginal, decide(f, CONVERT, keep = false, server = "MOBI"))
            assertEquals(f, KeepOriginal, decide(f, KEEP_ORIGINAL))
        }
    }

    @Test fun txtFollowsTheSettingAndOverride() {
        assertEquals(ConvertOnPhoneMobi, decide("TXT", CONVERT))
        assertEquals(KeepOriginal, decide("TXT", KEEP_ORIGINAL))
        assertEquals(KeepOriginal, decide("TXT", CONVERT, keep = true))
        assertEquals(ConvertOnPhoneMobi, decide("TXT", KEEP_ORIGINAL, keep = false))
        assertEquals(SendPlan.Server("mobi"), decide("TXT", CONVERT, server = "mobi"))
        assertEquals(KeepOriginal, decide("TXT", KEEP_ORIGINAL, server = "mobi"))
    }

    @Test fun unknownFormatsAreSentAsIs() {
        assertEquals(KeepOriginal, decide("CBZ", CONVERT))
        assertEquals(KeepOriginal, decide("DJVU", CONVERT))
    }

    @Test fun serverOffMeansOnPhoneMobi() {
        assertEquals(ConvertOnPhoneMobi, decide("EPUB", CONVERT, server = null))
        assertEquals(ConvertOnPhoneMobi, decide("EPUB", CONVERT, server = " "))
    }

    @Test fun onlyConvertibleFormatsOfferAChoice() {
        assertTrue(SendFormatDecision.hasChoice("EPUB"))
        assertTrue(SendFormatDecision.hasChoice("pdf"))
        assertTrue(SendFormatDecision.hasChoice("TXT"))
        assertFalse(SendFormatDecision.hasChoice("MOBI"))
        assertFalse(SendFormatDecision.hasChoice("EPUB", convertOnly = true))
    }

    @Test fun everyConvertibleFormatOffersAChoiceAndPdfDefaultsToKeep() {
        listOf("epub", "PDF", "TXT").forEach { assertEquals(it, AddPlan.Choice, addPlanFor(it)) }
        assertFalse(SendFormatDecision.keepByDefault(CONVERT))
        assertTrue(SendFormatDecision.keepByDefault(KEEP_ORIGINAL))
    }

    @Test fun serverOnMakesAnyNonKindleFormatConvertibleAndKeepStillSkipsIt() {
        assertEquals(AddPlan.Other, addPlanFor("CBZ"))
        assertEquals(AddPlan.Choice, addPlanFor("CBZ", serverAvailable = true))
        assertEquals(AddPlan.KindleReady, addPlanFor("MOBI", serverAvailable = true))
        assertEquals(SendPlan.Server("mobi"), decide("DOCX", CONVERT, server = "MOBI"))
        assertEquals(KeepOriginal, decide("DOCX", KEEP_ORIGINAL, server = "MOBI"))
        assertEquals(KeepOriginal, decide("DOCX", CONVERT, keep = true, server = "MOBI"))
        assertEquals(KeepOriginal, decide("DOCX", CONVERT))
    }

    @Test fun everyFormatBySettingByOverrideKeepMeansNoConversionAndNoServer() {
        listOf("EPUB", "PDF", "TXT", "DOCX", "CBZ", "MOBI", "AZW3").forEach { f ->
            listOf(CONVERT, KEEP_ORIGINAL).forEach { s ->
                listOf(true, false).forEach { serverOn ->
                    val plan = decide(f, s, keep = true, server = if (serverOn) "MOBI" else null)
                    assertEquals("$f $s $serverOn", KeepOriginal, plan)
                }
            }
        }
    }

    @Test fun addSheetPlanForKindleReadyAndOthersIsUnchanged() {
        assertEquals(AddPlan.KindleReady, addPlanFor("AZW3"))
        assertEquals(AddPlan.Other, addPlanFor("CBZ"))
    }

    // ── Setting persistence ──

    @Test fun settingDefaultsToKindleFormat() {
        assertEquals(CONVERT, SendFormatSettings(NullSharedPreferences()).format.value)
    }

    @Test fun settingPersists() {
        val prefs = NullSharedPreferences()
        val first = SendFormatSettings(prefs)
        first.set(KEEP_ORIGINAL)
        assertEquals(KEEP_ORIGINAL, first.format.value)
        assertEquals(KEEP_ORIGINAL, SendFormatSettings(prefs).format.value)
        first.set(CONVERT)
        assertEquals(CONVERT, SendFormatSettings(prefs).format.value)
    }
}
