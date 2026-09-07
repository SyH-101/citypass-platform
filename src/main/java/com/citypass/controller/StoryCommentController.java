package com.citypass.controller;

import com.citypass.dto.Result;
import com.citypass.entity.StoryComment;
import com.citypass.service.IStoryCommentService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/story-comments")
public class StoryCommentController {

    @Resource
    private IStoryCommentService storyCommentService;

    @PostMapping
    public Result add(@RequestBody StoryComment comment) {
        return storyCommentService.addComment(comment);
    }

    @GetMapping("/story/{storyId}")
    public Result list(@PathVariable Long storyId,
                       @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return storyCommentService.listComments(storyId, current);
    }

    @DeleteMapping("/{commentId}")
    public Result delete(@PathVariable Long commentId) {
        return storyCommentService.deleteComment(commentId);
    }
}
