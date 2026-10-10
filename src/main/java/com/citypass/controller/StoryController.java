package com.citypass.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.citypass.dto.Result;
import com.citypass.dto.UserDTO;
import com.citypass.entity.Story;
import com.citypass.service.IStoryService;
import com.citypass.utils.SystemConstants;
import com.citypass.utils.UserHolder;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 */
@RestController
@RequestMapping("/stories")
public class StoryController {

    @Resource
    private IStoryService storyService;

    @PutMapping("/like/{id}")
    public Result likeStory(@PathVariable("id") Long id) {
        return storyService.likeStory(id);
    }

    @GetMapping("/of/me")
    public Result queryMyStory(
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return storyService.queryByAuthor(UserHolder.getUser().getId(), current, true);
    }

    @GetMapping("/hot")
    public Result queryHotStory(
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return storyService.queryHotStory(current);
    }

    @GetMapping("/{id}")
    public Result queryStoryById(@PathVariable("id") Long id) {
        return storyService.queryStoryById(id);
    }

    @GetMapping("/likes/{id}")
    public Result queryStoryLikes(@PathVariable("id") Long id) {
        return storyService.queryStoryLikes(id);
    }

    @GetMapping("/of/user")
    public Result queryStoryByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        return storyService.queryByAuthor(id, current, false);
    }

    @GetMapping("/of/subscriptions")
    public Result queryStoriesOfSubscriptions(
            @RequestParam("lastId") Long max,
            @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
        return storyService.queryStoriesOfSubscriptions(max, offset);
    }
}
