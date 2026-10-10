package com.citypass.story;

import com.citypass.dto.Result;
import com.citypass.utils.UserHolder;
import org.springframework.web.bind.annotation.*;
import java.util.Collections;

@RestController
@RequestMapping("/stories")
public class StoryWriteController {

    private final StoryDraftService storyDraftService;
    private final StoryFileService storyFileService;

    public StoryWriteController(
            StoryDraftService storyDraftService, StoryFileService storyFileService) {
        this.storyDraftService = storyDraftService;
        this.storyFileService = storyFileService;
    }

    static long user() {
        if (UserHolder.getUser() == null) {
            throw new StoryProblem(401, "请先登录");
        }

        return UserHolder.getUser().getId();
    }

    @PostMapping({"", "/drafts"})
    public Result create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody StoryEditRequest request) {
        return Result.ok(storyDraftService.create(user(), idempotencyKey, request));
    }

    @PutMapping("/{id}")
    public Result edit(@PathVariable long id, @RequestBody StoryEditRequest request) {
        return Result.ok(storyDraftService.edit(id, user(), request));
    }

    @PostMapping("/{id}/publish")
    public Result publish(@PathVariable long id, @RequestParam long version) {
        return Result.ok(storyDraftService.publish(id, user(), version));
    }

    @DeleteMapping("/{id}")
    public Result delete(@PathVariable long id, @RequestParam long version) {
        return Result.ok(storyDraftService.delete(id, user(), version));
    }

    @PostMapping("/{id}/attachments")
    public Result upload(@PathVariable long id) {
        return Result.ok(storyFileService.requestUpload(id, user()));
    }

    @GetMapping("/{id}/attachments")
    public Result list(@PathVariable long id) {
        return Result.ok(storyFileService.list(id, user()));
    }

    @PostMapping("/attachments/{id}/confirm")
    public Result confirm(@PathVariable long id) {
        return Result.ok(storyFileService.confirm(id, user()));
    }

    @DeleteMapping("/attachments/{id}")
    public Result remove(@PathVariable long id) {
        storyFileService.remove(id, user());
        return Result.ok();
    }

    @GetMapping("/{storyId}/attachments/{id}/url")
    public Result read(@PathVariable long storyId, @PathVariable long id) {
        Long currentUserId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
        return Result.ok(
                Collections.singletonMap(
                        "url", storyFileService.readUrl(storyId, id, currentUserId)));
    }
}
