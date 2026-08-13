package com.userexec.soneme.mimic

import com.google.zxing.BarcodeFormat

enum class CollectionKind(val dbValue: String, val label: String) {
    WALLET("wallet", "Wallet"),
    TEMPORARY("temporary", "Temporary");

    companion object {
        fun fromDb(value: String) = entries.firstOrNull { it.dbValue == value } ?: WALLET
    }
}

enum class ItemKind(val dbValue: String, val label: String) {
    CODE("code", "Code"),
    PLAIN_TEXT("plain_text", "Plain text and headings");

    companion object {
        fun fromDb(value: String?) = entries.firstOrNull { it.dbValue == value } ?: CODE
    }
}

enum class QrConvention(val dbValue: String, val label: String) {
    TEXT("text", "Text"),
    URL("url", "URL"),
    WIFI("wifi", "Wi-Fi"),
    CONTACT("contact", "Contact"),
    EMAIL("email", "Email"),
    PHONE("phone", "Phone"),
    SMS("sms", "SMS"),
    LOCATION("location", "Location"),
    CALENDAR("calendar", "Calendar"),
    RAW("raw", "Raw");

    companion object {
        fun fromDb(value: String?) = entries.firstOrNull { it.dbValue == value } ?: RAW
    }
}

data class FormatChoice(val label: String, val format: BarcodeFormat)

data class GenerateFormatChoice(
    val label: String,
    val kind: ItemKind,
    val format: BarcodeFormat? = null
)

object Formats {
    val all = listOf(
        FormatChoice("QR Code", BarcodeFormat.QR_CODE),
        FormatChoice("UPC-A", BarcodeFormat.UPC_A),
        FormatChoice("UPC-E", BarcodeFormat.UPC_E),
        FormatChoice("EAN-8", BarcodeFormat.EAN_8),
        FormatChoice("EAN-13", BarcodeFormat.EAN_13),
        FormatChoice("Code 39", BarcodeFormat.CODE_39),
        FormatChoice("Code 93", BarcodeFormat.CODE_93),
        FormatChoice("Code 128", BarcodeFormat.CODE_128),
        FormatChoice("ITF", BarcodeFormat.ITF),
        FormatChoice("Codabar", BarcodeFormat.CODABAR),
        FormatChoice("PDF417", BarcodeFormat.PDF_417),
        FormatChoice("Data Matrix", BarcodeFormat.DATA_MATRIX),
        FormatChoice("Aztec", BarcodeFormat.AZTEC)
    )

    val generateChoices: List<GenerateFormatChoice> =
        listOf(GenerateFormatChoice(ItemKind.PLAIN_TEXT.label, ItemKind.PLAIN_TEXT)) +
            all.map { GenerateFormatChoice(it.label, ItemKind.CODE, it.format) }

    fun label(format: BarcodeFormat): String = all.firstOrNull { it.format == format }?.label ?: format.name

    fun displayLabel(record: CodeRecord): String =
        if (record.kind == ItemKind.PLAIN_TEXT) ItemKind.PLAIN_TEXT.label else label(record.format)

    fun fromName(name: String): BarcodeFormat =
        runCatching { BarcodeFormat.valueOf(name) }.getOrDefault(BarcodeFormat.QR_CODE)

    fun generateIndex(kind: ItemKind, format: BarcodeFormat): Int = generateChoices.indexOfFirst {
        it.kind == kind && (kind == ItemKind.PLAIN_TEXT || it.format == format)
    }.coerceAtLeast(0)
}

data class CodeRecord(
    val uid: String,
    val name: String,
    val collection: CollectionKind,
    val kind: ItemKind = ItemKind.CODE,
    val format: BarcodeFormat = BarcodeFormat.QR_CODE,
    val convention: QrConvention?,
    val payload: String,
    val sortOrder: Int,
    val displayRotation: Int = -1,
    val displayInverted: Boolean = false
)

data class TextFieldRecord(
    val heading: String,
    val text: String,
    val sortOrder: Int = 0
)

data class PhotoRecord(
    val id: String,
    val itemUid: String,
    val fileName: String,
    val sortOrder: Int
)
