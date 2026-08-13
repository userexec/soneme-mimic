package com.userexec.soneme.mimic

import com.google.zxing.BarcodeFormat

enum class CollectionKind(val dbValue: String, val label: String) {
    WALLET("wallet", "Wallet"),
    TEMPORARY("temporary", "Temporary");

    companion object {
        fun fromDb(value: String) = entries.firstOrNull { it.dbValue == value } ?: WALLET
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

    fun label(format: BarcodeFormat): String = all.firstOrNull { it.format == format }?.label ?: format.name
    fun fromName(name: String): BarcodeFormat = runCatching { BarcodeFormat.valueOf(name) }.getOrDefault(BarcodeFormat.QR_CODE)
}

data class CodeRecord(
    val uid: String,
    val name: String,
    val collection: CollectionKind,
    val format: BarcodeFormat,
    val convention: QrConvention?,
    val payload: String,
    val sortOrder: Int,
    val displayRotation: Int = -1,
    val displayInverted: Boolean = false
)
