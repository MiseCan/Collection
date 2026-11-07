package com.collection

import com.collection.domain.Attachment
import com.collection.ui.hasNativeViewer
import com.collection.ui.readOriginalTextPage
import org.junit.Assert.*
import org.junit.Test

class OriginalContentTest {
    @Test fun imagesAndTextUseNativeViewerWhileDocumentsGoToExternalApps() {
        fun asset(name: String, mime: String) = Attachment(name = name, mime = mime, size = 1, path = "unused")
        assertTrue(asset("照片.jpg", "image/jpeg").hasNativeViewer())
        assertTrue(asset("说明.txt", "text/plain").hasNativeViewer())
        assertFalse(asset("文档.pdf", "application/pdf").hasNativeViewer())
        assertFalse(asset("资料.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document").hasNativeViewer())
    }
    @Test fun fullOriginalCanBeReadInBoundedPagesWithoutDroppingUnicode() {
        val original = "原文🙂与汉字\n".repeat(80) + "最后一段"
        val pages = mutableListOf<String>()
        var page = 0
        do {
            val (text, more) = original.reader().buffered().use { readOriginalTextPage(it, page++, 23) }
            pages += text
            assertFalse(text.isNotEmpty() && Character.isLowSurrogate(text.first()))
            assertFalse(text.isNotEmpty() && Character.isHighSurrogate(text.last()))
        } while (more)
        assertEquals(original, pages.joinToString(""))
    }
}
