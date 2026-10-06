package com.jamhowman.beastbrowser

import com.jamhowman.beastbrowser.downloads.DownloadCenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaveAsPdfTest {
    @Test fun usesPageTitle() {
        assertEquals("Football scores.pdf", DownloadCenter.pdfName("Football scores", "https://www.flashscore.com/"))
    }

    @Test fun fallsBackToHostThenPage() {
        assertEquals("bbc.co.uk.pdf", DownloadCenter.pdfName("  ", "https://www.bbc.co.uk/news"))
        assertEquals("bbc.co.uk.pdf", DownloadCenter.pdfName(null, "https://www.bbc.co.uk/news"))
        assertEquals("page.pdf", DownloadCenter.pdfName(null, null))
    }

    @Test fun keepsExistingPdfExtension() {
        assertEquals("report.PDF", DownloadCenter.pdfName("report.PDF", "https://example.com/report.PDF"))
    }

    @Test fun cleansUnsafeCharacters() {
        assertEquals("Live_ Arsenal v Spurs _ BBC.pdf", DownloadCenter.pdfName("Live: Arsenal v Spurs | BBC", "https://bbc.co.uk"))
    }

    @Test fun longTitlesKeepTheExtension() {
        val name = DownloadCenter.pdfName("a".repeat(400), "https://example.com")
        assertTrue(name.endsWith(".pdf"))
        assertTrue(name.length <= 120)
    }
}
