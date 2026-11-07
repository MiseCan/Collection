package com.collection.domain

import java.util.Locale

/** Stable IDs are persisted independently of Android resource IDs. */
enum class EntryIcon(val key: String, val label: String) {
    AUTO("auto", "自动识别"),
    TEXT("text", "文字"),
    LINK("link", "链接"),
    IMAGE("image", "图片"),
    EMAIL("email", "邮箱"),
    PDF("pdf", "PDF"),
    DOCUMENT("document", "文档"),
    SHEET("sheet", "表格"),
    SLIDES("slides", "演示"),
    AUDIO("audio", "音频"),
    VIDEO("video", "视频"),
    ARCHIVE("archive", "压缩包"),
    CODE("code", "代码"),
    WEB("web", "网页"),
    PHONE("phone", "电话"),
    CONTACT("contact", "联系人"),
    LOCATION("location", "位置"),
    CALENDAR("calendar", "日程"),
    TASK("task", "待办"),
    BOOK("book", "书籍"),
    CHAT("chat", "聊天"),
    IDEA("idea", "灵感"),
    SCIENCE("science", "研究"),
    RECEIPT("receipt", "票据"),
    PAYMENT("payment", "支付"),
    FOLDER("folder", "文件夹"),
    ATTACHMENT("attachment", "附件"),
    COLLECTION("collection", "多种资料"),
    SECURITY("security", "私密"),
    FAVORITE("favorite", "收藏"),
    QR("qr", "二维码"),
    DOWNLOAD("download", "下载"),
    CLOUD("cloud", "云端"),
    DATABASE("database", "数据库"),
    WORK("work", "工作"),
    PERSON("person", "人物"),
    APP("app", "安装包"),
    FONT("font", "字体");
    companion object { fun fromKey(key: String): EntryIcon? = entries.firstOrNull { it.key == key } }
}

fun Entry.resolvedIcon(): EntryIcon {
    EntryIcon.fromKey(iconKey)?.takeIf { it != EntryIcon.AUTO }?.let { return it }
    if (attachments.isNotEmpty()) {
        val types = attachments.map { it.inferredIcon() }.distinct()
        return if (types.size > 1) EntryIcon.COLLECTION else types.first()
    }
    val text = body.trim().take(4096)
    return when {
        EMAIL.containsMatchIn(text) -> EntryIcon.EMAIL
        URL.containsMatchIn(text) -> EntryIcon.LINK
        PHONE.matches(text) -> EntryIcon.PHONE
        else -> EntryIcon.TEXT
    }
}

fun Attachment.inferredIcon(): EntryIcon {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val type = mime.lowercase(Locale.ROOT)
    return when {
        ext == "pdf" || type == "application/pdf" -> EntryIcon.PDF
        ext in setOf("doc", "docx", "odt", "rtf") || "word" in type -> EntryIcon.DOCUMENT
        ext in setOf("xls", "xlsx", "csv", "tsv", "ods") || "spreadsheet" in type || "excel" in type -> EntryIcon.SHEET
        ext in setOf("ppt", "pptx", "odp", "key") || "presentation" in type || "powerpoint" in type -> EntryIcon.SLIDES
        type.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "svg", "heic", "bmp") -> EntryIcon.IMAGE
        type.startsWith("audio/") || ext in setOf("mp3", "wav", "m4a", "ogg", "flac", "aac") -> EntryIcon.AUDIO
        type.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "webm", "avi") -> EntryIcon.VIDEO
        ext in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz") -> EntryIcon.ARCHIVE
        ext in setOf("json", "xml", "kt", "java", "py", "js", "ts", "css", "yaml", "yml", "c", "cpp", "rs", "sh") -> EntryIcon.CODE
        ext in setOf("html", "htm", "url", "webloc") -> EntryIcon.WEB
        ext in setOf("eml", "msg") -> EntryIcon.EMAIL
        ext in setOf("epub", "mobi", "azw", "azw3") -> EntryIcon.BOOK
        ext in setOf("ttf", "otf", "woff", "woff2") -> EntryIcon.FONT
        ext in setOf("apk", "aab") -> EntryIcon.APP
        ext in setOf("ics", "ical") -> EntryIcon.CALENDAR
        ext == "vcf" -> EntryIcon.CONTACT
        type.startsWith("text/") || ext in setOf("txt", "md", "markdown") -> EntryIcon.TEXT
        else -> EntryIcon.ATTACHMENT
    }
}

private val EMAIL = Regex("mailto:|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
private val URL = Regex("https?://|www\\.", RegexOption.IGNORE_CASE)
private val PHONE = Regex("\\+?[0-9() -]{7,20}")
