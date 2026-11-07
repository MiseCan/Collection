package com.collection

import com.collection.domain.*
import org.junit.Assert.*
import org.junit.Test

class EntryIconsTest {
    private fun file(name: String, mime: String = "application/octet-stream") = Attachment(name = name, mime = mime, size = 1, path = "unused")
    @Test fun commonFileTypesHaveDistinctOfficialIconCategories() {
        val cases = mapOf("原件.pdf" to EntryIcon.PDF, "报告.docx" to EntryIcon.DOCUMENT,
            "表格.xlsx" to EntryIcon.SHEET, "数据.csv" to EntryIcon.SHEET, "幻灯.pptx" to EntryIcon.SLIDES,
            "照片.JPG" to EntryIcon.IMAGE, "录音.mp3" to EntryIcon.AUDIO, "录像.mp4" to EntryIcon.VIDEO,
            "资料.zip" to EntryIcon.ARCHIVE, "数据.json" to EntryIcon.CODE, "页面.html" to EntryIcon.WEB,
            "邮件.eml" to EntryIcon.EMAIL, "电子书.epub" to EntryIcon.BOOK, "安装包.apk" to EntryIcon.APP,
            "字体.ttf" to EntryIcon.FONT, "日程.ics" to EntryIcon.CALENDAR, "联系人.vcf" to EntryIcon.CONTACT,
            "笔记.md" to EntryIcon.TEXT, "未知.xyz" to EntryIcon.ATTACHMENT)
        cases.forEach { (name, expected) -> assertEquals(name, expected, file(name).inferredIcon()) }
    }
    @Test fun textSourcesAndMixedMaterialsInferTheirTypes() {
        assertEquals(EntryIcon.LINK, Entry(body = "https://example.com/page").resolvedIcon())
        assertEquals(EntryIcon.EMAIL, Entry(body = "name@example.com").resolvedIcon())
        assertEquals(EntryIcon.PHONE, Entry(body = "+86 13800138000").resolvedIcon())
        assertEquals(EntryIcon.TEXT, Entry(body = "普通笔记").resolvedIcon())
        assertEquals(EntryIcon.COLLECTION, Entry(attachments = listOf(file("a.pdf"), file("b.png"))).resolvedIcon())
        assertEquals(EntryIcon.IMAGE, Entry(attachments = listOf(file("a.jpg"), file("b.png"))).resolvedIcon())
    }
    @Test fun manualChoiceWinsAndAutomaticOrUnknownKeysFallBackSafely() {
        val entry = Entry(body = "https://example.com", iconKey = "email")
        assertEquals(EntryIcon.EMAIL, entry.resolvedIcon())
        assertEquals(EntryIcon.LINK, entry.copy(iconKey = "auto").resolvedIcon())
        assertEquals(EntryIcon.LINK, entry.copy(iconKey = "future_unknown").resolvedIcon())
    }
    @Test fun iconIdsAreStableAndUnique() {
        assertEquals(38, EntryIcon.entries.size)
        assertEquals(38, EntryIcon.entries.map { it.key }.distinct().size)
        EntryIcon.entries.forEach { assertEquals(it, EntryIcon.fromKey(it.key)) }
    }
}
