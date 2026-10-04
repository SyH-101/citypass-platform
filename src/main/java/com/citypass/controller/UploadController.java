package com.citypass.controller;
import com.citypass.story.StoryProblem;
import org.springframework.web.bind.annotation.*;
/** Filename-based writes cannot establish ownership. All new uploads use StoryWriteController. */
@RestController
@RequestMapping("/upload")
public class UploadController {
    @RequestMapping(value="/story",method={RequestMethod.POST,RequestMethod.DELETE})
    public void retired() { throw new StoryProblem(410,"旧图片写入接口已停用，请使用笔记附件上传与移除接口"); }
}
