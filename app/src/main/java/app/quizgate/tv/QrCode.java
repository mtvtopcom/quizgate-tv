package app.quizgate.tv;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.HashMap;
import java.util.Map;

/** 二维码生成：只做 byte 模式，内容是一个局域网 URL。 */
public final class QrCode {

    public static Bitmap make(String content, int sizePx) {
        if (content == null || content.isEmpty() || sizePx <= 0) return null;
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 2);
            BitMatrix m = new QRCodeWriter().encode(
                    content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            int[] px = new int[sizePx * sizePx];
            for (int y = 0; y < sizePx; y++) {
                int off = y * sizePx;
                for (int x = 0; x < sizePx; x++) {
                    px[off + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            bmp.setPixels(px, 0, sizePx, 0, 0, sizePx, sizePx);
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }
}
