package com.userexec.soneme.mimic

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

object BarcodeCodec {
    fun encode(payload: String, format: BarcodeFormat): BitMatrix {
        val hints = hashMapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to 0,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        return MultiFormatWriter().encode(payload, format, 1, 1, hints)
    }

    fun canEncode(payload: String, format: BarcodeFormat): Boolean =
        payload.isNotEmpty() && runCatching { encode(payload, format) }.isSuccess

    fun isOneDimensional(format: BarcodeFormat): Boolean = when (format) {
        BarcodeFormat.UPC_A, BarcodeFormat.UPC_E, BarcodeFormat.EAN_8, BarcodeFormat.EAN_13,
        BarcodeFormat.CODE_39, BarcodeFormat.CODE_93, BarcodeFormat.CODE_128,
        BarcodeFormat.ITF, BarcodeFormat.CODABAR -> true
        else -> false
    }

    fun quietModules(format: BarcodeFormat): Int = when (format) {
        BarcodeFormat.UPC_A, BarcodeFormat.UPC_E, BarcodeFormat.EAN_8, BarcodeFormat.EAN_13,
        BarcodeFormat.CODE_39, BarcodeFormat.CODE_93, BarcodeFormat.CODE_128,
        BarcodeFormat.ITF, BarcodeFormat.CODABAR -> 10
        BarcodeFormat.DATA_MATRIX -> 2
        else -> 4
    }
}
