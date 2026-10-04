package com.citypass.story;

import com.citypass.config.StoryFileProperties;
import lombok.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Iterator;
import java.util.Locale;

@Component
public class ImageInspector {
    private final StoryFileProperties limits;
    public ImageInspector(StoryFileProperties limits) { this.limits = limits; }

    @Value public static class Info {
        String format;
        long size;
        int width;
        int height;
    }

    public Info inspect(InputStream input, long actualSize) throws IOException {
        if (actualSize <= 0 || actualSize > limits.getMaxBytes()) {
            throw new StoryProblem(422, "图片实际大小超过限制或为空");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int)Math.min(actualSize, 65536));
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = input.read(buffer)) != -1) {
            total += n;
            if (total > limits.getMaxBytes()) throw new StoryProblem(422, "图片实际大小超过限制");
            bytes.write(buffer, 0, n);
        }
        if (total != actualSize) throw new StoryProblem(422, "对象长度发生变化，请重新上传");
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new StoryProblem(422, "文件内容不是有效图片");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("jpeg") && !format.equals("png")) {
                    throw new StoryProblem(422, "仅支持 JPEG 和 PNG 图片内容");
                }
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > limits.getMaxDimension()
                        || height > limits.getMaxDimension() || (long)width * height > limits.getMaxPixels()) {
                    throw new StoryProblem(422, "图片尺寸或像素数量超过限制");
                }
                // Decode after checking dimensions; headers alone do not prove a valid image.
                BufferedImage decoded = reader.read(0);
                if (decoded == null) throw new StoryProblem(422, "图片解码失败");
                decoded.flush();
                return new Info(format, total, width, height);
            } finally { reader.dispose(); }
        } catch (StoryProblem e) { throw e; }
        catch (IOException | RuntimeException e) { throw new StoryProblem(422, "图片内容损坏或无法解码"); }
    }
}
