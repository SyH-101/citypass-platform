package com.citypass.controller;

import com.citypass.dto.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class UploadControllerTest {

    @TempDir
    Path uploadRoot;

    @Test
    void rejectsNonImageAndPathTraversalDelete() throws Exception {
        UploadController controller = new UploadController();
        ReflectionTestUtils.setField(controller, "uploadDir", uploadRoot.toString());

        MockMultipartFile executable = new MockMultipartFile(
                "file", "attack.exe", "application/octet-stream", new byte[]{1, 2});
        assertFalse(controller.uploadImage(executable).getSuccess());

        Path outside = uploadRoot.getParent().resolve("outside.txt");
        Files.write(outside, new byte[]{1});
        Result deleted = controller.deleteStoryImage("../outside.txt");
        assertFalse(deleted.getSuccess());
        assertTrue(Files.exists(outside));
        Files.deleteIfExists(outside);
    }

    @Test
    void storesAllowedImageInsideConfiguredRoot() {
        UploadController controller = new UploadController();
        ReflectionTestUtils.setField(controller, "uploadDir", uploadRoot.toString());
        MockMultipartFile image = new MockMultipartFile(
                "file", "photo.png", "image/png", new byte[]{1, 2, 3});

        Result result = controller.uploadImage(image);

        assertTrue(result.getSuccess());
        String publicName = String.valueOf(result.getData());
        assertTrue(publicName.startsWith("/stories/"));
        assertTrue(Files.exists(uploadRoot.resolve(publicName.substring(1))));
    }
}
