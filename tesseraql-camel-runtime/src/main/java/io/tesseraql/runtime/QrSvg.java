package io.tesseraql.runtime;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/**
 * Renders a string as an inline QR SVG (docs/credential-lifecycle.md): zxing computes the
 * module matrix and this class emits one path of unit squares — no imaging stack, no client
 * scripting, theme-neutral (currentColor). Used for the TOTP enrollment's otpauth URI so an
 * authenticator app enrolls with a scan instead of manual entry.
 */
final class QrSvg {

    private QrSvg() {
    }

    static String render(String content) {
        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0);
        } catch (WriterException ex) {
            throw new IllegalStateException("QR encoding failed", ex);
        }
        int size = matrix.getWidth();
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (matrix.get(x, y)) {
                    path.append("M").append(x).append(' ').append(y).append("h1v1h-1z");
                }
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + size + " " + size
                + "\" width=\"192\" height=\"192\" role=\"img\""
                + " aria-label=\"TOTP enrollment QR code\" shape-rendering=\"crispEdges\">"
                + "<path fill=\"currentColor\" d=\"" + path + "\"/></svg>";
    }
}
