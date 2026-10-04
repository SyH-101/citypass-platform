package com.citypass.story;

import com.citypass.dto.Result;
import com.citypass.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;

@RestController
@RequestMapping("/stories")
public class StoryWriteController {
    private final StoryDraftService drafts;
    private final StoryFileService files;
    public StoryWriteController(StoryDraftService drafts,StoryFileService files) { this.drafts=drafts; this.files=files; }
    static long user() {
        if(UserHolder.getUser()==null) throw new StoryProblem(401,"请先登录");
        return UserHolder.getUser().getId();
    }
    @PostMapping({"","/drafts"})
    public Result create(@RequestHeader("Idempotency-Key") String key,@RequestBody StoryEditRequest request) { return Result.ok(drafts.create(user(),key,request)); }
    @PutMapping("/{id}")
    public Result edit(@PathVariable long id,@RequestBody StoryEditRequest request) { return Result.ok(drafts.edit(id,user(),request)); }
    @PostMapping("/{id}/publish")
    public Result publish(@PathVariable long id,@RequestParam long version) { return Result.ok(drafts.publish(id,user(),version)); }
    @DeleteMapping("/{id}")
    public Result delete(@PathVariable long id,@RequestParam long version) { return Result.ok(drafts.delete(id,user(),version)); }
    @PostMapping("/{id}/attachments")
    public Result upload(@PathVariable long id) { return Result.ok(files.requestUpload(id,user())); }
    @GetMapping("/{id}/attachments")
    public Result list(@PathVariable long id) { return Result.ok(files.list(id,user())); }
    @PostMapping("/attachments/{id}/confirm")
    public Result confirm(@PathVariable long id) { return Result.ok(files.confirm(id,user())); }
    @DeleteMapping("/attachments/{id}")
    public Result remove(@PathVariable long id) { files.remove(id,user()); return Result.ok(); }
    @GetMapping("/{storyId}/attachments/{id}/url")
    public Result read(@PathVariable long storyId,@PathVariable long id) {
        Long current=UserHolder.getUser()==null?null:UserHolder.getUser().getId();
        return Result.ok(Collections.singletonMap("url",files.readUrl(storyId,id,current)));
    }
}
