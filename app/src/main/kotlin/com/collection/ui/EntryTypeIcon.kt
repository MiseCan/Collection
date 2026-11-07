package com.collection.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.collection.R
import com.collection.domain.EntryIcon

@Composable
fun EntryTypeIcon(icon: EntryIcon, modifier: Modifier = Modifier, description: String? = null) {
    val resource = when (icon) {
        EntryIcon.AUTO -> R.drawable.symbol_auto
        EntryIcon.TEXT -> R.drawable.symbol_text
        EntryIcon.LINK -> R.drawable.symbol_link
        EntryIcon.IMAGE -> R.drawable.symbol_image
        EntryIcon.EMAIL -> R.drawable.symbol_email
        EntryIcon.PDF -> R.drawable.symbol_pdf
        EntryIcon.DOCUMENT -> R.drawable.symbol_document
        EntryIcon.SHEET -> R.drawable.symbol_sheet
        EntryIcon.SLIDES -> R.drawable.symbol_slides
        EntryIcon.AUDIO -> R.drawable.symbol_audio
        EntryIcon.VIDEO -> R.drawable.symbol_video
        EntryIcon.ARCHIVE -> R.drawable.symbol_archive
        EntryIcon.CODE -> R.drawable.symbol_code
        EntryIcon.WEB -> R.drawable.symbol_web
        EntryIcon.PHONE -> R.drawable.symbol_phone
        EntryIcon.CONTACT -> R.drawable.symbol_contact
        EntryIcon.LOCATION -> R.drawable.symbol_location
        EntryIcon.CALENDAR -> R.drawable.symbol_calendar
        EntryIcon.TASK -> R.drawable.symbol_task
        EntryIcon.BOOK -> R.drawable.symbol_book
        EntryIcon.CHAT -> R.drawable.symbol_chat
        EntryIcon.IDEA -> R.drawable.symbol_idea
        EntryIcon.SCIENCE -> R.drawable.symbol_science
        EntryIcon.RECEIPT -> R.drawable.symbol_receipt
        EntryIcon.PAYMENT -> R.drawable.symbol_payment
        EntryIcon.FOLDER -> R.drawable.symbol_folder
        EntryIcon.ATTACHMENT -> R.drawable.symbol_attachment
        EntryIcon.COLLECTION -> R.drawable.symbol_collection
        EntryIcon.SECURITY -> R.drawable.symbol_security
        EntryIcon.FAVORITE -> R.drawable.symbol_favorite
        EntryIcon.QR -> R.drawable.symbol_qr
        EntryIcon.DOWNLOAD -> R.drawable.symbol_download
        EntryIcon.CLOUD -> R.drawable.symbol_cloud
        EntryIcon.DATABASE -> R.drawable.symbol_database
        EntryIcon.WORK -> R.drawable.symbol_work
        EntryIcon.PERSON -> R.drawable.symbol_person
        EntryIcon.APP -> R.drawable.symbol_app
        EntryIcon.FONT -> R.drawable.symbol_font
    }
    Icon(painterResource(resource), description, modifier, tint = MaterialTheme.colorScheme.primary)
}
