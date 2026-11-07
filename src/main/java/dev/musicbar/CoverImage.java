package dev.musicbar;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

 
final class CoverImage {
    private CoverImage() {}
    static byte[] toPng(byte[] bytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) throw new IOException("Unsupported cover image format");
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", out)) throw new IOException("PNG encoder unavailable");
            return out.toByteArray();
        } finally { image.flush(); }
    }
}
