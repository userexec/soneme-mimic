package com.userexec.soneme.mimic

import android.net.Uri
import com.google.zxing.Result
import com.google.zxing.client.result.AddressBookParsedResult
import com.google.zxing.client.result.CalendarParsedResult
import com.google.zxing.client.result.EmailAddressParsedResult
import com.google.zxing.client.result.GeoParsedResult
import com.google.zxing.client.result.ParsedResultType
import com.google.zxing.client.result.ResultParser
import com.google.zxing.client.result.URIParsedResult
import com.google.zxing.client.result.SMSParsedResult
import com.google.zxing.client.result.TelParsedResult
import com.google.zxing.client.result.WifiParsedResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class QrFields(val convention: QrConvention, val values: LinkedHashMap<String, String>)

object QrPayload {
    fun parse(result: Result): QrFields {
        val parsed = ResultParser.parseResult(result)
        return when (parsed.getType()) {
            ParsedResultType.URI -> QrFields(QrConvention.URL, linkedMapOf("URL" to (parsed as URIParsedResult).getURI()))
            ParsedResultType.WIFI -> {
                val p = parsed as WifiParsedResult
                QrFields(QrConvention.WIFI, linkedMapOf(
                    "SSID" to p.getSsid().orEmpty(),
                    "Security" to p.getNetworkEncryption().orEmpty(),
                    "Password" to p.getPassword().orEmpty(),
                    "Hidden" to p.isHidden().toString()
                ))
            }
            ParsedResultType.ADDRESSBOOK -> {
                val p = parsed as AddressBookParsedResult
                QrFields(QrConvention.CONTACT, linkedMapOf(
                    "Name" to p.getNames()?.firstOrNull().orEmpty(),
                    "Organization" to p.getOrg().orEmpty(),
                    "Phone" to p.getPhoneNumbers()?.firstOrNull().orEmpty(),
                    "Email" to p.getEmails()?.firstOrNull().orEmpty(),
                    "Address" to p.getAddresses()?.firstOrNull().orEmpty(),
                    "URL" to p.getURLs()?.firstOrNull().orEmpty()
                ))
            }
            ParsedResultType.EMAIL_ADDRESS -> {
                val p = parsed as EmailAddressParsedResult
                QrFields(QrConvention.EMAIL, linkedMapOf(
                    "Address" to p.getTos()?.firstOrNull().orEmpty(),
                    "Subject" to p.getSubject().orEmpty(),
                    "Body" to p.getBody().orEmpty()
                ))
            }
            ParsedResultType.TEL -> {
                val p = parsed as TelParsedResult
                QrFields(QrConvention.PHONE, linkedMapOf("Number" to p.getNumber().orEmpty()))
            }
            ParsedResultType.SMS -> {
                val p = parsed as SMSParsedResult
                QrFields(QrConvention.SMS, linkedMapOf(
                    "Number" to p.getNumbers()?.firstOrNull().orEmpty(),
                    "Message" to p.getBody().orEmpty()
                ))
            }
            ParsedResultType.GEO -> {
                val p = parsed as GeoParsedResult
                QrFields(QrConvention.LOCATION, linkedMapOf(
                    "Latitude" to compactDouble(p.getLatitude()),
                    "Longitude" to compactDouble(p.getLongitude())
                ))
            }
            ParsedResultType.CALENDAR -> {
                val p = parsed as CalendarParsedResult
                QrFields(QrConvention.CALENDAR, linkedMapOf(
                    "Title" to p.getSummary().orEmpty(),
                    "Start" to formatCalendar(p.getStartTimestamp(), p.isStartAllDay()),
                    "End" to if (p.getEndTimestamp() < 0) "" else formatCalendar(p.getEndTimestamp(), p.isEndAllDay()),
                    "Location" to p.getLocation().orEmpty(),
                    "Description" to p.getDescription().orEmpty()
                ))
            }
            else -> QrFields(QrConvention.RAW, linkedMapOf("Payload" to result.getText().orEmpty()))
        }
    }

    fun blank(convention: QrConvention): LinkedHashMap<String, String> = when (convention) {
        QrConvention.TEXT -> linkedMapOf("Content" to "")
        QrConvention.URL -> linkedMapOf("URL" to "")
        QrConvention.WIFI -> linkedMapOf("SSID" to "", "Security" to "WPA", "Password" to "", "Hidden" to "false")
        QrConvention.CONTACT -> linkedMapOf("Name" to "", "Organization" to "", "Phone" to "", "Email" to "", "Address" to "", "URL" to "")
        QrConvention.EMAIL -> linkedMapOf("Address" to "", "Subject" to "", "Body" to "")
        QrConvention.PHONE -> linkedMapOf("Number" to "")
        QrConvention.SMS -> linkedMapOf("Number" to "", "Message" to "")
        QrConvention.LOCATION -> linkedMapOf("Latitude" to "", "Longitude" to "")
        QrConvention.CALENDAR -> linkedMapOf("Title" to "", "Start" to "", "End" to "", "Location" to "", "Description" to "")
        QrConvention.RAW -> linkedMapOf("Payload" to "")
    }

    fun serialize(convention: QrConvention, values: Map<String, String>): String = when (convention) {
        QrConvention.TEXT -> values["Content"].orEmpty()
        QrConvention.RAW -> values["Payload"].orEmpty()
        QrConvention.URL -> values["URL"].orEmpty()
        QrConvention.WIFI -> buildString {
            append("WIFI:T:").append(wifiEscape(values["Security"].orEmpty().ifBlank { "nopass" }))
            append(";S:").append(wifiEscape(values["SSID"].orEmpty()))
            val password = values["Password"].orEmpty()
            if (password.isNotEmpty()) append(";P:").append(wifiEscape(password))
            if (values["Hidden"].orEmpty().equals("true", true) || values["Hidden"] == "1" || values["Hidden"].orEmpty().equals("yes", true)) {
                append(";H:true")
            }
            append(";;")
        }
        QrConvention.CONTACT -> buildString {
            append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
            appendVCard("FN", values["Name"])
            appendVCard("ORG", values["Organization"])
            appendVCard("TEL", values["Phone"])
            appendVCard("EMAIL", values["Email"])
            values["Address"]?.takeIf { it.isNotBlank() }?.let { append("ADR:;;").append(vcardEscape(it)).append(";;;;\r\n") }
            appendVCard("URL", values["URL"])
            append("END:VCARD")
        }
        QrConvention.EMAIL -> {
            val address = values["Address"].orEmpty()
            val params = mutableListOf<String>()
            values["Subject"]?.takeIf { it.isNotEmpty() }?.let { params += "subject=${Uri.encode(it)}" }
            values["Body"]?.takeIf { it.isNotEmpty() }?.let { params += "body=${Uri.encode(it)}" }
            "mailto:$address" + if (params.isEmpty()) "" else "?" + params.joinToString("&")
        }
        QrConvention.PHONE -> "tel:${values["Number"].orEmpty()}"
        QrConvention.SMS -> "SMSTO:${values["Number"].orEmpty()}:${values["Message"].orEmpty()}"
        QrConvention.LOCATION -> "geo:${values["Latitude"].orEmpty()},${values["Longitude"].orEmpty()}"
        QrConvention.CALENDAR -> buildString {
            append("BEGIN:VEVENT\r\n")
            appendICal("SUMMARY", values["Title"])
            append("DTSTART:").append(toICalDate(values["Start"].orEmpty())).append("\r\n")
            values["End"]?.takeIf { it.isNotBlank() }?.let { append("DTEND:").append(toICalDate(it)).append("\r\n") }
            appendICal("LOCATION", values["Location"])
            appendICal("DESCRIPTION", values["Description"])
            append("END:VEVENT")
        }
    }

    fun fieldsFromStored(convention: QrConvention, payload: String): LinkedHashMap<String, String> {
        if (convention == QrConvention.TEXT) return linkedMapOf("Content" to payload)
        if (convention == QrConvention.RAW) return linkedMapOf("Payload" to payload)
        val fake = Result(payload, null, null, com.google.zxing.BarcodeFormat.QR_CODE)
        val parsed = parse(fake)
        return if (parsed.convention == convention) parsed.values else blank(convention).also {
            if (it.size == 1) it[it.keys.first()] = payload
        }
    }

    fun requiredFieldsValid(convention: QrConvention, values: Map<String, String>): Boolean = when (convention) {
        QrConvention.TEXT -> !values["Content"].isNullOrEmpty()
        QrConvention.RAW -> !values["Payload"].isNullOrEmpty()
        QrConvention.URL -> !values["URL"].isNullOrBlank()
        QrConvention.WIFI -> !values["SSID"].isNullOrEmpty()
        QrConvention.CONTACT -> values.values.any { it.isNotBlank() }
        QrConvention.EMAIL -> !values["Address"].isNullOrBlank()
        QrConvention.PHONE -> !values["Number"].isNullOrBlank()
        QrConvention.SMS -> !values["Number"].isNullOrBlank()
        QrConvention.LOCATION -> {
            val lat = values["Latitude"]?.toDoubleOrNull()
            val lon = values["Longitude"]?.toDoubleOrNull()
            lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0
        }
        QrConvention.CALENDAR -> isCalendarDate(values["Start"].orEmpty()) && (values["End"].isNullOrBlank() || isCalendarDate(values["End"].orEmpty()))
    }

    private fun StringBuilder.appendVCard(name: String, value: String?) {
        value?.takeIf { it.isNotBlank() }?.let { append(name).append(':').append(vcardEscape(it)).append("\r\n") }
    }
    private fun StringBuilder.appendICal(name: String, value: String?) {
        value?.takeIf { it.isNotBlank() }?.let { append(name).append(':').append(icalEscape(it)).append("\r\n") }
    }
    private fun wifiEscape(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")
    private fun vcardEscape(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,")
    private fun icalEscape(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,")
    private fun compactDouble(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private val inputDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
    private val displayAllDay = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
    private val inputDateTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { isLenient = false }
    private val icalDate = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val icalDateTime = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)

    @Synchronized private fun isCalendarDate(s: String): Boolean {
        val shapeOk = s.matches(Regex("""\d{4}-\d{2}-\d{2}""")) ||
            s.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}"""))
        if (!shapeOk) return false
        return runCatching {
            if (s.length == 10) inputDate.parse(s) else inputDateTime.parse(s)
        }.getOrNull() != null
    }

    @Synchronized private fun toICalDate(s: String): String {
        val date = if (s.length == 10) inputDate.parse(s) else inputDateTime.parse(s)
            ?: throw IllegalArgumentException("Invalid calendar date")
        return if (s.length == 10) icalDate.format(date) else icalDateTime.format(date)
    }

    @Synchronized private fun formatCalendar(timestamp: Long, allDay: Boolean): String {
        if (timestamp < 0) return ""
        return (if (allDay) displayAllDay else inputDateTime).format(Date(timestamp))
    }
}
