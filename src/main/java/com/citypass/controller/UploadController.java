package com.citypass.controller;

import cn.hutool.core.util.StrUtil;
import com.citypass.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    private static final Set<String> ALLOWED_SUFFIXES = new HashSet<>(
            Arrays.asList("jpg", "jpeg", "png", "gif", "webp"));

    @Value("${citypass.upload-dir:./data/images}")
    private String uploadDir;

    @PostMapping("story")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        if (image == null || image.isEmpty()) {
            return Result.fail("图片不能为空");
        }
        String original = image.getOriginalFilename();
        String suffix = original == null ? "" : StrUtil.subAfter(original, ".", true).toLowerCase(Locale.ROOT);
        if (!ALLOWED_SUFFIXES.contains(suffix)
                || image.getContentType() == null
                || !image.getContentType().toLowerCase(Locale.ROOT).startsWith("image/")) {
            return Result.fail("仅支持 jpg、jpeg、png、gif、webp 图片");
        }

        String publicName = createNewFileName(suffix);
        try {
            Path target = resolveInsideUploadRoot(publicName);
            Files.createDirectories(target.getParent());
            image.transferTo(target.toFile());
            log.debug("文件上传成功，{}", publicName);
            return Result.ok(publicName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @DeleteMapping("/story")
    public Result deleteStoryImage(@RequestParam("name") String filename) {
        try {
            Path file = resolveInsideUploadRoot(filename);
            if (!Files.isRegularFile(file)) {
                return Result.fail("文件不存在或名称错误");
            }
            Files.delete(file);
            return Result.ok();
        } catch (IOException | IllegalArgumentException e) {
            return Result.fail("错误的文件名称");
        }
    }

    private String createNewFileName(String suffix) {
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        return StrUtil.format("/stories/{}/{}/{}.{}", d1, d2, name, suffix);
    }

    private Path resolveInsideUploadRoot(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException("empty filename");
        }
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        String relative = filename.replace('\\', '/');
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("path traversal");
        }
        return resolved;
    }
}
