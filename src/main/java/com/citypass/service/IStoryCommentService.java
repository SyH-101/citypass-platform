package com.citypass.service;

import com.citypass.dto.Result;
import com.citypass.entity.StoryComment;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @since 2021-12-22
 */
public interface IStoryCommentService extends IService<StoryComment> {

    Result addComment(StoryComment comment);

    Result listComments(Long storyId, Integer current);

    Result deleteComment(Long commentId);
}
