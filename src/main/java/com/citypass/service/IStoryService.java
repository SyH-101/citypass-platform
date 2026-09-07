package com.citypass.service;

import com.citypass.dto.Result;
import com.citypass.entity.Story;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @since 2021-12-22
 */
public interface IStoryService extends IService<Story> {

    Result queryHotStory(Integer current);

    Result queryStoryById(Long id);

    Result likeStory(Long id);

    Result queryStoryLikes(Long id);

    Result saveStory(Story story);

    Result queryStoriesOfSubscriptions(Long max, Integer offset);

}
