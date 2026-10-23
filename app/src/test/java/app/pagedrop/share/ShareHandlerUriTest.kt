package app.pagedrop.share

import android.net.TestUri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareHandlerUriTest {
    @Test
    fun onlyContentUrisAreAcceptedFromShareIntents() {
        assertTrue(ShareHandler.isAcceptedShareUri(TestUri("content://com.example.files/book.epub")))
        assertTrue(ShareHandler.isAcceptedShareUri(TestUri("CONTENT://x/y", scheme = "CONTENT")))
    }

    @Test
    fun fileAndOtherSchemesAreRejected() {
        assertFalse(ShareHandler.isAcceptedShareUri(TestUri("file:///data/data/app.pagedrop/shared_prefs/x.xml", scheme = "file")))
        assertFalse(ShareHandler.isAcceptedShareUri(TestUri("https://example.com/a.epub", scheme = "https")))
        assertFalse(ShareHandler.isAcceptedShareUri(TestUri("/sdcard/book.epub", scheme = null)))
        assertFalse(ShareHandler.isAcceptedShareUri(null))
    }
}
