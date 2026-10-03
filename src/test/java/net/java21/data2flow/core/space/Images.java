package net.java21.data2flow.core.space;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** 평면도 테스트 이미지 */
final class Images {

    private Images() {
    }

    static byte[] png(int width, int height) {
        return write("png", width, height, BufferedImage.TYPE_INT_ARGB);
    }

    static byte[] jpeg(int width, int height) {
        return write("jpg", width, height, BufferedImage.TYPE_INT_RGB);
    }

    static byte[] svg(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] write(String format, int width, int height, int type) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(width, height, type), format, out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
