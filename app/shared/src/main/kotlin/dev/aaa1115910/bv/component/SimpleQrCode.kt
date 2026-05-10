package dev.aaa1115910.bv.component

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Low-memory static QR code for TV login screens.
 *
 * This intentionally avoids the animated MaterialShapeQr implementation because low-end
 * Android TV boxes can run out of memory or become unresponsive while composing and drawing
 * the Lottie/vector-shape based QR animation.
 */
@Composable
fun SimpleQrCode(
    modifier: Modifier = Modifier,
    content: String,
    sizePx: Int = 360
) {
    if (content.isBlank()) return

    val bitmap = remember(content, sizePx) {
        generateQrBitmap(
            content = content,
            sizePx = sizePx
        )
    }

    DisposableEffect(bitmap) {
        onDispose {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    Image(
        modifier = modifier
            .background(ComposeColor.White)
            .padding(12.dp),
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null
    )
}

private fun generateQrBitmap(
    content: String,
    sizePx: Int
): Bitmap {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1
    )

    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        hints
    )

    val pixels = IntArray(sizePx * sizePx) { index ->
        val x = index % sizePx
        val y = index / sizePx
        if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE
    }

    return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565).apply {
        setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
    }
}
