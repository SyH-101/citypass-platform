package com.citypass.story;

import com.citypass.config.StoryFileProperties;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class ImageInspectorTest {
    private byte[] image(int width,int height,String format) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB),format,bytes));
        return bytes.toByteArray();
    }
    @Test void validatesActualPngAndJpegBytes() throws Exception {
        ImageInspector inspector=new ImageInspector(new StoryFileProperties());
        for(String format:new String[]{"png","jpeg"}) {
            byte[] bytes=image(12,9,format);
            ImageInspector.Info info=inspector.inspect(new ByteArrayInputStream(bytes),bytes.length);
            assertEquals(format,info.getFormat()); assertEquals(12,info.getWidth()); assertEquals(9,info.getHeight());
        }
    }
    @Test void rejectsDisguisedDataAndTruncatedImages() throws Exception {
        ImageInspector inspector=new ImageInspector(new StoryFileProperties());
        assertThrows(StoryProblem.class,() -> inspector.inspect(new ByteArrayInputStream("not png".getBytes()),7));
        byte[] good=image(20,20,"png"), bad=java.util.Arrays.copyOf(good,45);
        assertThrows(StoryProblem.class,() -> inspector.inspect(new ByteArrayInputStream(bad),bad.length));
    }
    @Test void checksLimitsBeforeDecodeAndBoundsStream() throws Exception {
        StoryFileProperties p=new StoryFileProperties(); p.setMaxPixels(100);
        ImageInspector inspector=new ImageInspector(p);
        byte[] bytes=image(11,10,"png");
        assertThrows(StoryProblem.class,() -> inspector.inspect(new ByteArrayInputStream(bytes),bytes.length));
        p.setMaxBytes(64);
        assertThrows(StoryProblem.class,() -> inspector.inspect(new ByteArrayInputStream(new byte[100]),64));
        assertThrows(StoryProblem.class,() -> inspector.inspect(new ByteArrayInputStream(new byte[65]),65));
    }
}
