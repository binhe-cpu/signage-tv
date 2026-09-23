package com.laodao.signage

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码生成。用 zxing 的 core 模块，纯 Java，不牵扯 Android 图形栈。
 */
object QrCode {

    /**
     * 把文本编成二维码位图。sizePx 是想要的正方形边长（实际会按模块数取整）。
     * 失败返回 null，调用方自己决定怎么兜底。
     */
    fun bitmap(text: String, sizePx: Int): Bitmap? {
        if (text.isEmpty() || sizePx <= 0) return null
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1
            )
            val matrix = QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints
            )
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        } catch (e: Exception) {
            Log.w(Config.TAG, "二维码生成失败：$text", e)
            null
        }
    }
}
