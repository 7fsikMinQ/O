package kr.bojohelp.file;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import net.coobird.thumbnailator.Thumbnails;

/**
 * 업로드 이미지 검증·정리: 실제 내용(매직바이트)으로 JPEG/PNG 만 허용하고, 너무 작은 이미지와 비정상적으로 큰 이미지(디코딩 폭탄)를 거부하며,
 * 재인코딩으로 EXIF(위치·기기 정보)를 제거하고 긴 변을 제한한다. 사진 방향(EXIF orientation)은 라이브러리가 적용한 뒤 제거한다.
 */
public final class ImageSanitizer {

    public record Result(byte[] jpeg, int width, int height) {}

    /** 형식·크기가 부적절한 이미지. 메시지는 사용자에게 보여 줄 수 있는 쉬운 말이다. */
    public static class InvalidImageException extends RuntimeException {
        public InvalidImageException(String message) {
            super(message);
        }
    }

    private static final int MIN_SIDE = 200;
    private static final long MAX_PIXELS = 40_000_000L;

    private ImageSanitizer() {}

    public static Result sanitize(byte[] data, int maxSide) {
        return sanitize(data, maxSide, MIN_SIDE);
    }

    /** minSide: 짧은 변의 최소 픽셀(서명 이미지는 작아도 된다). */
    public static Result sanitize(byte[] data, int maxSide, int minSide) {
        if (!isJpeg(data) && !isPng(data)) {
            throw new InvalidImageException("JPG 또는 PNG 사진만 올릴 수 있어요. (아이폰 사진은 '가장 호환되는 형식'으로 바꾸거나 캡처본을 올려 주세요)");
        }
        int w;
        int h;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new InvalidImageException("사진을 읽을 수 없어요. 다른 사진을 올려 주세요.");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                w = reader.getWidth(0);
                h = reader.getHeight(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new InvalidImageException("사진을 읽을 수 없어요. 다른 사진을 올려 주세요.");
        }
        if (Math.min(w, h) < minSide) throw new InvalidImageException("사진이 너무 작아요. 가로·세로 " + minSide + "픽셀 이상으로 올려 주세요.");
        if ((long) w * h > MAX_PIXELS) throw new InvalidImageException("사진이 너무 커요. 크기를 줄여서 올려 주세요.");

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            var builder = Thumbnails.of(new ByteArrayInputStream(data));
            if (Math.max(w, h) > maxSide) builder.size(maxSide, maxSide); else builder.scale(1.0);
            builder.outputFormat("jpg").outputQuality(0.85).toOutputStream(out);
            byte[] jpeg = out.toByteArray();
            BufferedImage result = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (result == null) throw new InvalidImageException("사진을 처리하지 못했어요. 다른 사진을 올려 주세요.");
            return new Result(jpeg, result.getWidth(), result.getHeight());
        } catch (IOException e) {
            throw new InvalidImageException("사진을 처리하지 못했어요. 다른 사진을 올려 주세요.");
        }
    }

    private static boolean isJpeg(byte[] d) {
        return d.length > 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF;
    }

    private static boolean isPng(byte[] d) {
        return d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G';
    }
}
