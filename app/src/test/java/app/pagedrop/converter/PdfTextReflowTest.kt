package app.pagedrop.converter

import app.pagedrop.converter.Block.Heading
import app.pagedrop.converter.Block.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfTextReflowTest {

    private val header = "The Hunger Games – Suzanne Collins"

    private fun page(n: Int, body: String) = "$n | P a g e  $header \n$body\n"

    private val page3 = page(
        3,
        """
        When I wake up, the other side of the bed is cold. My 
        fingers stretch out, seeking Prim's warmth but finding 
        only the rough canvas cover of the mattress. She must 
        have had bad dreams and climbed in with our mother. Of 
        course she did. This is the day of the 
        reaping. 
        I prop myself up on one elbow. There's enough light in 
        the bedroom to see them. My little sister, Prim, curled 
        up on her side, cocooned in my mother's body, their 
        cheeks pressed together. 
        """.trimIndent()
    )

    private val page4 = page(
        4,
        """
        In sleep, my mother looks younger, still worn but not 
        so beaten down. Prim's face is as fresh as a raindrop, 
        as lovely as the primrose for which she was named. My 
        mother was very beautiful once, too. Or so they tell 
        me. 
        Sitting at Prim's knees, guarding her, is the world's 
        ugliest cat. Mashed-in nose, half of one ear missing, 
        eyes the color of rotting squash. Prim named him 
        Buttercup, insisting that his muddy yellow coat 
        matched the bright flower. 
        """.trimIndent()
    )

    private val page5 = page(
        5,
        """
        He hates me. Or at least distrusts me. Even though it 
        was years ago, I think he remembers how I tried to 
        drown him in a bucket when Prim brought him home. 
        Scrawny kitten, belly swollen with worms, crawling 
        with fleas. 
        """.trimIndent()
    )

    private fun paragraphs(blocks: List<Block>) = blocks.filterIsInstance<Paragraph>().map { it.text }

    @Test
    fun stripsSpacedOutPageHeaderLines() {
        val blocks = PdfTextReflow.reflow(listOf(page3, page4, page5))
        val all = blocks.joinToString("\n") { (it as? Paragraph)?.text ?: (it as Heading).text }
        assertFalse(all.contains("P a g e"))
        assertFalse(all.contains("Suzanne Collins"))
    }

    @Test
    fun stripsRepeatedHeaderWithoutPageNumberAndBarePageNumbers() {
        val topics = listOf("apples", "bridges", "candles", "dragons", "engines")
        val pages = (10..14).mapIndexed { i, n ->
            "Flowers Title Running Head\nThis is some body text about ${topics[i]} that goes on and on\nand wraps to a second line here.\n$n"
        }
        val blocks = PdfTextReflow.reflow(pages)
        val text = paragraphs(blocks).joinToString(" ")
        assertFalse(text.contains("Running Head"))
        assertFalse(Regex("(^|\\s)1[0-4]$").containsMatchIn(text))
        assertTrue(text.contains("This is some body text about apples"))
    }

    @Test
    fun stripsPageNOfM() {
        val pages = listOf("Page 1 of 9\nHello there friend of mine and others\n", "Page 2 of 9\nSecond page text goes here and there\n")
        val text = paragraphs(PdfTextReflow.reflow(pages)).joinToString(" ")
        assertFalse(text.contains("Page"))
    }

    @Test
    fun unwrapsLinesWithSpacesAndNoNewlines() {
        val blocks = PdfTextReflow.reflow(listOf(page3, page4, page5))
        val first = paragraphs(blocks).first()
        assertTrue(first.startsWith("When I wake up, the other side of the bed is cold. My fingers stretch out"))
        assertFalse(first.contains("\n"))
        assertFalse(first.contains("  "))
    }

    @Test
    fun splitsParagraphsOnShortLineEndingInPunctuation() {
        val blocks = PdfTextReflow.reflow(listOf(page3, page4, page5))
        val paras = paragraphs(blocks)
        assertTrue(paras[0].endsWith("This is the day of the reaping."))
        assertTrue(paras[1].startsWith("I prop myself up on one elbow."))
        assertTrue(paras.any { it.endsWith("Or so they tell me.") })
        assertTrue(paras.any { it.startsWith("Sitting at Prim's knees") })
    }

    @Test
    fun completeSentenceAtPageEndDoesNotMergeWithNextPage() {
        val paras = paragraphs(PdfTextReflow.reflow(listOf(page3, page4, page5)))
        assertTrue(paras.any { it.startsWith("I prop myself up") && it.endsWith("pressed together.") })
        assertTrue(paras.any { it.startsWith("In sleep, my mother") })
    }

    @Test
    fun mergesMidSentencePageBreak() {
        val p1 = "Header Line X\nThe quick brown fox jumped over the lazy dog and kept\nrunning through the forest until the river where it\nstopped to drink and\n"
        val p2 = "Header Line X\nrest before going on. Then it left.\n"
        val p3 = "Header Line X\nAnother page of text that fills the line out to the edge\nof the page nicely.\n"
        val paras = paragraphs(PdfTextReflow.reflow(listOf(p1, p2, p3)))
        assertEquals(2, paras.size)
        assertTrue(paras[0].contains("stopped to drink and rest before going on. Then it left."))
    }

    @Test
    fun pageBoundaryAfterCompleteSentenceStartsNewParagraph() {
        val full = "This line is a long line that reaches the full width of text."
        val p1 = "H H H\n$full\n$full\n$full\n"
        val p2 = "H H H\nNext paragraph starts here with a capital letter and runs.\n$full\n$full\n"
        val p3 = "H H H\n$full\n"
        val paras = paragraphs(PdfTextReflow.reflow(listOf(p1, p2, p3)))
        assertTrue(paras.any { it.startsWith("Next paragraph starts here") })
        assertFalse(paras.any { it.contains("$full Next paragraph") })
    }

    @Test
    fun deHyphenatesWhenNextLineStartsLowercase() {
        val text = "The quick brown fox is a good exam-\nple of an animal that jumps a lot over things.\n"
        val paras = paragraphs(PdfTextReflow.reflow(listOf(text)))
        assertEquals(1, paras.size)
        assertTrue(paras[0].contains("good example of an animal"))
    }

    @Test
    fun keepsHyphenWhenNextLineStartsUppercase() {
        val text = "The quick brown fox visited the Anglo-\nSaxon village where it met some friends today.\n"
        val paras = paragraphs(PdfTextReflow.reflow(listOf(text)))
        assertTrue(paras[0].contains("Anglo- Saxon"))
    }

    @Test
    fun blankLineSplitsParagraphs() {
        val text = "First paragraph text without end punctuation\n\nSecond paragraph text here\n"
        assertEquals(2, paragraphs(PdfTextReflow.reflow(listOf(text))).size)
    }

    @Test
    fun dialogueAfterShortLineStartsNewParagraph() {
        val long = "He walked down the long road toward the village with the dog."
        val mid = "He walked down the long road toward the village."
        val text = "$long\n$long\n$long\n$mid\n\"Where are you?\" she asked.\n“I am here,” he said.\n$long\n$long\n"
        val paras = paragraphs(PdfTextReflow.reflow(listOf(text)))
        assertTrue(paras.any { it.startsWith("“I am here,” he said.") })
        assertTrue(paras.any { it.startsWith("\"Where are you?\" she asked.") })
    }

    @Test
    fun detectsChapterAndPartHeadings() {
        val p1 = "PART I\nCHAPTER 1\nTHE REAPING\nSome text that is long enough to be a real line of text here.\n"
        val p2 = "More text that is also long enough to be a line of text on page two.\nChapter Two\nAnd the story goes on for a good while longer than before.\n"
        val p3 = "Final page text that is long enough to be a line of text on page three.\n"
        val blocks = PdfTextReflow.reflow(listOf(p1, p2, p3))
        val headings = blocks.filterIsInstance<Heading>().map { it.text }
        assertTrue(headings.contains("PART I"))
        assertTrue(headings.contains("CHAPTER 1 – THE REAPING"))
        assertTrue(headings.contains("Chapter Two"))
    }

    @Test
    fun allCapsStandaloneLineIsHeading() {
        val text = "Some body text here.\n\nTHE REAPING\n\nWhen I wake up the day is cold and grey over town.\n"
        val blocks = PdfTextReflow.reflow(listOf(text))
        assertTrue(blocks.contains(Heading("THE REAPING")))
    }

    @Test
    fun dropsOcrMarginJunk() {
        val places = listOf("store", "market", "bakery", "dairy")
        val pages = (0..3).map {
            "Ie\nHe went to the ${places[it]} to buy some bread and milk for the family\n)f\nand then he came home with the groceries.\nd\nIn\n"
        }
        val paras = paragraphs(PdfTextReflow.reflow(pages))
        val joined = paras.joinToString(" ")
        assertFalse(joined.contains(")f"))
        assertFalse(Regex("\\bIe\\b").containsMatchIn(joined))
        assertTrue(joined.contains("to buy some bread and milk for the family and then he came home"))
    }

    @Test
    fun htmlHasNoBrAndPagebreaksOnlyBeforeHeadings() {
        val pages = listOf(page3, page4, page5)
        val html = PdfTextReflow.buildHtml("Hunger & Games", PdfTextReflow.reflow(pages))
        assertFalse(html.contains("<br"))
        assertFalse(html.contains("<mbp:pagebreak/>"))
        assertTrue(html.contains("<title>Hunger &amp; Games</title>"))
        assertTrue(html.contains("<p>When I wake up"))
    }

    @Test
    fun htmlEmitsH2AndPagebreakBeforeHeadingsButNotFirst() {
        val html = PdfTextReflow.buildHtml(
            "T",
            listOf(Heading("CHAPTER 1"), Paragraph("a <b> & c"), Heading("CHAPTER 2"), Paragraph("d")),
        )
        assertEquals(1, Regex("<mbp:pagebreak/>").findAll(html).count())
        assertTrue(html.contains("<h2>CHAPTER 1</h2>"))
        assertTrue(html.contains("<p>a &lt;b&gt; &amp; c</p>"))
        assertTrue(html.indexOf("<mbp:pagebreak/>") < html.indexOf("<h2>CHAPTER 2</h2>"))
    }

    @Test
    fun emptyInput() {
        assertTrue(PdfTextReflow.reflow(emptyList()).isEmpty())
        assertTrue(PdfTextReflow.reflow(listOf("", "  \n")).isEmpty())
    }
}
