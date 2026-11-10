package com.listingprep

import com.listingprep.data.Item
import com.listingprep.data.Photo
import com.listingprep.data.Settings
import com.listingprep.data.Status
import com.listingprep.data.listingDescription
import com.listingprep.data.readyToShare
import com.listingprep.data.stripDefaults
import com.listingprep.data.withDefaults
import com.listingprep.ml.Describer
import com.listingprep.ml.PriceLookup
import com.listingprep.ml.ProductLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the rules that quietly decide what ends up in a listing. Runs on every build. */
class RulesTest {

    // ---- reading the AI's answer ------------------------------------------

    @Test fun parsesTitleDescriptionIdentifiedAndMsrp() {
        val raw = """
            TITLE: Bose QuietComfort 45 headphones, black
            DESCRIPTION:
            Wireless noise cancelling headphones with long battery life.
            - USB-C charging
            - Includes case
            IDENTIFIED: yes
            MSRP: $329.99
        """.trimIndent()
        val l = Describer.parse(raw)
        assertEquals("Bose QuietComfort 45 headphones, black", l.title)
        assertTrue(l.identified)
        assertEquals(329.99, l.msrp!!, 0.001)
        assertFalse("control lines must not leak into the description", l.description.contains("IDENTIFIED"))
        assertFalse(l.description.contains("MSRP"))
        assertTrue(l.description.startsWith("Wireless noise cancelling"))
    }

    @Test fun unknownMsrpAndUnidentified() {
        val l = Describer.parse("TITLE: Leather wallet\nDESCRIPTION:\nGenuine leather bifold wallet with six card slots.\nIDENTIFIED: no\nMSRP: unknown")
        assertFalse(l.identified)
        assertNull(l.msrp)
    }

    @Test fun msrpWithThousandsSeparator() {
        assertEquals(1299.0, Describer.parse("TITLE: x\nDESCRIPTION:\nSomething useful here for sure.\nMSRP: HK$1,299")!!.msrp!!, 0.001)
    }

    @Test fun stripsMarkdownBold() {
        val l = Describer.parse("**TITLE:** Desk lamp\n**DESCRIPTION:**\nAdjustable LED desk lamp with three brightness levels.")
        assertEquals("Desk lamp", l.title)
    }

    @Test fun cutOffAnswerEndsOnAFullSentence() {
        val l = Describer.parse("TITLE: Desk lamp\nDESCRIPTION:\nAdjustable LED desk lamp with three brightness levels. Great for reading and late-night work, with a flexi")
        assertEquals("Adjustable LED desk lamp with three brightness levels.", l.description)
    }

    @Test fun completeAnswerIsNotTrimmed() {
        val l = Describer.parse("TITLE: Desk lamp\nDESCRIPTION:\nAdjustable LED desk lamp.\n- Three brightness levels\nIDENTIFIED: no\nMSRP: unknown")
        assertTrue(l.description.endsWith("- Three brightness levels"))
    }

        // ---- garbage detector --------------------------------------------------

    @Test fun goodListingPasses() {
        val l = Describer.Companion.Listing(
            "IKEA Markus office chair, black",
            "Comfortable high-back office chair with a mesh back and adjustable height.\n- Black mesh back\n- Tilt works",
            true,
        )
        assertFalse(Describer.looksBroken(l))
    }

    @Test fun gibberishIsCaught() {
        val l = Describer.Companion.Listing("Xqzt brrk", "zxcvbn qwrtpl mnbvcx lkjhgf zxcvbn qwrtpl mnbvcx lkjhgf ### @@@ ~~~ ^^^", true)
        assertTrue(Describer.looksBroken(l))
    }

    @Test fun repetitionLoopIsCaught() {
        val l = Describer.Companion.Listing("Chair", "great chair great chair great chair great chair great chair great chair great chair", true)
        assertTrue(Describer.looksBroken(l))
    }

    @Test fun emptyAnswerIsCaught() {
        assertTrue(Describer.looksBroken(Describer.Companion.Listing("", "", true)))
    }

    // ---- listing defaults --------------------------------------------------

    private val settings = Settings(footer = "Pickup only. Cash or bank transfer.")

    @Test fun defaultsAreAppendedOnce() {
        val text = withDefaults("Great lamp.", "Good", settings)
        assertEquals("Great lamp.\n\nCondition: Good\n\nPickup only. Cash or bank transfer.", text)
        // Running it again (e.g. after a rewrite) must not stack a second copy.
        assertEquals(text, withDefaults(text, "Good", settings))
    }

    @Test fun conditionChangeReplacesTheLine() {
        val text = withDefaults(withDefaults("Great lamp.", "Good", settings), "Like new", settings)
        assertTrue(text.contains("Condition: Like new"))
        assertFalse(text.contains("Condition: Good"))
    }

    @Test fun stripLeavesOnlyTheAiText() {
        assertEquals("Great lamp.", stripDefaults(withDefaults("Great lamp.", "Fair", settings), settings))
    }

    @Test fun sharingDoesNotDuplicateDefaults() {
        val item = Item(photos = listOf(Photo("file:///x.jpg", 0, 0.0)), description = withDefaults("Great lamp.", "Good", settings), condition = "Good")
        assertEquals(item.description, item.listingDescription(settings))
    }

    @Test fun sharingAddsDefaultsToOldItems() {
        val item = Item(photos = listOf(Photo("file:///x.jpg", 0, 0.0)), description = "Great lamp.", condition = "Good")
        assertTrue(item.listingDescription(settings).endsWith("Pickup only. Cash or bank transfer."))
    }

    @Test fun readyToShareNeedsCheckTitlePriceAndDescription() {
        val base = Item(photos = listOf(Photo("file:///x.jpg", 0, 0.0)), title = "Lamp", description = "Nice lamp.", price = "20", status = Status.READY)
        assertFalse(base.readyToShare())
        assertTrue(base.copy(checked = true).readyToShare())
        assertFalse(base.copy(checked = true, price = "").readyToShare())
    }

    // ---- barcodes ----------------------------------------------------------

    @Test fun validBarcodeIsLookedUp() {
        assertEquals("4006381333931", ProductLookup.retailCode(listOf("QR: https://example.com", "EAN-13: 4006381333931")))
    }

    @Test fun badCheckDigitIsNeverSent() {
        assertNull(ProductLookup.retailCode(listOf("EAN-13: 4006381333932")))
    }

    @Test fun qrCodesAreNotRetailCodes() {
        assertNull(ProductLookup.retailCode(listOf("QR: 4006381333931")))
    }

    // ---- price search answer -----------------------------------------------

    @Test fun priceLineIsRead() {
        val e = PriceLookup.parse("Used ones go for about this.\nPRICE: 1,200 | LOW: 900 | HIGH: 1500 | CURRENCY: hkd", emptyList())
        assertEquals(1200.0, e.price!!, 0.001)
        assertEquals(900.0, e.low!!, 0.001)
        assertEquals(1500.0, e.high!!, 0.001)
        assertEquals("HKD", e.currency)
    }

    @Test fun unknownPrice() {
        assertNull(PriceLookup.parse("Couldn't find anything.\nPRICE: unknown", emptyList()).price)
    }

    @Test fun rounding() {
        assertEquals("330", PriceLookup.round(329.99))
        assertEquals("12.50", PriceLookup.round(12.5))
    }
}
