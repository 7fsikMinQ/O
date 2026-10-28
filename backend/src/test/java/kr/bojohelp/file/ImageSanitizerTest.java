package kr.bojohelp.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

public class ImageSanitizerTest {

    public static byte[] image(int w, int h, String format) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    @Test
    void largeJpegIsScaledDownAndReencoded() throws Exception {
        var r = ImageSanitizer.sanitize(image(3000, 2000, "jpg"), 1600);
        assertThat(Math.max(r.width(), r.height())).isEqualTo(1600);
        assertThat(r.jpeg()[0] & 0xFF).isEqualTo(0xFF);   // JPEG 매직바이트
        assertThat(r.jpeg()[1] & 0xFF).isEqualTo(0xD8);
    }

    @Test
    void smallEnoughImageKeepsItsSize() throws Exception {
        var r = ImageSanitizer.sanitize(image(800, 600, "png"), 1600);
        assertThat(r.width()).isEqualTo(800);
        assertThat(r.height()).isEqualTo(600);
    }

    @Test
    void rejectsTooSmallNonImageAndUnsupportedFormats() throws Exception {
        assertThatThrownBy(() -> ImageSanitizer.sanitize(image(50, 50, "jpg"), 1600)).isInstanceOf(ImageSanitizer.InvalidImageException.class);
        assertThatThrownBy(() -> ImageSanitizer.sanitize("not an image".getBytes(), 1600)).isInstanceOf(ImageSanitizer.InvalidImageException.class);
        assertThatThrownBy(() -> ImageSanitizer.sanitize(image(400, 400, "gif"), 1600)).isInstanceOf(ImageSanitizer.InvalidImageException.class);
        assertThatThrownBy(() -> ImageSanitizer.sanitize(new byte[0], 1600)).isInstanceOf(ImageSanitizer.InvalidImageException.class);
    }

    @Test
    void rejectsHeaderOnlyFakeJpeg() {
        byte[] fake = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01, 0x02, 0x03, 0x04};
        assertThatThrownBy(() -> ImageSanitizer.sanitize(fake, 1600)).isInstanceOf(ImageSanitizer.InvalidImageException.class);
    }
}
