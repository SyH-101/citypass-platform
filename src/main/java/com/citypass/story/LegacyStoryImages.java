package com.citypass.story;

import com.citypass.utils.UserHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.file.*;
import java.util.*;

/**
 * Read existing, DB-owned legacy files. Upload/delete-by-filename is retired.
 */
@RestController
public class LegacyStoryImages {

    private final StoryTransactions storyTransactions;
    private final Path imageRoot;

    public LegacyStoryImages(
            StoryTransactions storyTransactions,
            @Value("${citypass.upload-dir:./data/images}") String directory) {
        this.storyTransactions = storyTransactions;
        imageRoot = Paths.get(directory).toAbsolutePath().normalize();
    }

    @GetMapping("/stories/{id}/legacy-images/{position}")
    public ResponseEntity<Resource> read(@PathVariable long id, @PathVariable int position)
            throws Exception {
        Map<String, Object> story = storyTransactions.story(id, false);
        StoryFileService.assertReadable(
                story, UserHolder.getUser() == null ? null : UserHolder.getUser().getId());
        List<String> images = StoryDraftService.legacy((String) story.get("images"));
        if (position < 0 || position >= images.size()) {
            throw StoryProblem.missing();
        }

        String imagePath = images.get(position);
        if (!imagePath.matches(
                "/stories/[0-9a-f]{1,2}/[0-9a-f]{1,2}/[A-Za-z0-9-]+\\.(jpg|jpeg|png|gif|webp)")) {
            throw StoryProblem.missing();
        }

        Path file = imageRoot.resolve(imagePath.substring(1)).normalize();
        if (!file.startsWith(imageRoot)
                || !Files.isRegularFile(file)
                || !file.toRealPath().startsWith(imageRoot.toRealPath())) {
            throw StoryProblem.missing();
        }

        String contentType = Files.probeContentType(file);
        // Minimal Java 8 runtime images may not ship the OS MIME database. The path
        // already passed the fixed image-extension allowlist and real-path boundary.
        if (contentType == null) {
            String extension = imagePath.substring(imagePath.lastIndexOf('.') + 1);
            contentType =
                    "jpg".equals(extension) || "jpeg".equals(extension)
                            ? "image/jpeg"
                            : "image/" + extension;
        }

        if (contentType == null || !contentType.startsWith("image/")) {
            throw StoryProblem.missing();
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(contentType))
                .header("X-Content-Type-Options", "nosniff")
                .body(new UrlResource(file.toUri()));
    }
}
