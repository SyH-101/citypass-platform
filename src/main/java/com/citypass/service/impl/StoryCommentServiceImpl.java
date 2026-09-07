package com.citypass.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.citypass.dto.Result;
import com.citypass.entity.Story;
import com.citypass.entity.StoryComment;
import com.citypass.entity.User;
import com.citypass.mapper.StoryCommentMapper;
import com.citypass.mapper.StoryMapper;
import com.citypass.service.IStoryCommentService;
import com.citypass.service.IUserService;
import com.citypass.utils.SystemConstants;
import com.citypass.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @since 2021-12-22
 */
@Service
public class StoryCommentServiceImpl extends ServiceImpl<StoryCommentMapper, StoryComment> implements IStoryCommentService {

    @Resource
    private StoryMapper storyMapper;

    @Resource
    private IUserService userService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result addComment(StoryComment comment) {
        if (comment == null || comment.getStoryId() == null || comment.getContent() == null
                || comment.getContent().trim().isEmpty() || comment.getContent().trim().length() > 255) {
            return Result.fail("评论内容不能为空且不能超过 255 个字符");
        }
        if (UserHolder.getUser() == null) {
            return Result.fail("用户未登录");
        }
        Story story = storyMapper.selectById(comment.getStoryId());
        if (story == null) {
            return Result.fail("动态不存在");
        }
        long parentId = comment.getParentId() == null ? 0L : comment.getParentId();
        long answerId = comment.getAnswerId() == null ? 0L : comment.getAnswerId();
        if ((parentId > 0 && !belongsToStory(parentId, comment.getStoryId()))
                || (answerId > 0 && !belongsToStory(answerId, comment.getStoryId()))) {
            return Result.fail("回复目标不存在或不属于当前动态");
        }
        comment.setId(null)
                .setUserId(UserHolder.getUser().getId())
                .setParentId(parentId)
                .setAnswerId(answerId)
                .setContent(comment.getContent().trim())
                .setLiked(0)
                .setStatus(0);
        if (!save(comment)) {
            return Result.fail("评论发布失败");
        }
        int changed = storyMapper.update(null, new UpdateWrapper<Story>()
                .setSql("comments = COALESCE(comments, 0) + 1")
                .eq("id", comment.getStoryId()));
        if (changed != 1) {
            throw new IllegalStateException("评论计数更新失败: " + comment.getStoryId());
        }
        return Result.ok(comment.getId());
    }

    @Override
    public Result listComments(Long storyId, Integer current) {
        if (storyId == null) {
            return Result.fail("动态编号不能为空");
        }
        int pageNo = current == null || current < 1 ? 1 : current;
        Page<StoryComment> page = lambdaQuery()
                .eq(StoryComment::getStoryId, storyId)
                .eq(StoryComment::getStatus, 0)
                .orderByAsc(StoryComment::getId)
                .page(new Page<>(pageNo, SystemConstants.MAX_PAGE_SIZE));
        enrichAuthors(page.getRecords());
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result deleteComment(Long commentId) {
        if (commentId == null || UserHolder.getUser() == null) {
            return Result.fail("评论编号不能为空或用户未登录");
        }
        StoryComment existing = getById(commentId);
        if (existing == null || !UserHolder.getUser().getId().equals(existing.getUserId())) {
            return Result.fail("评论不存在或无权删除");
        }
        boolean removed = lambdaUpdate()
                .eq(StoryComment::getId, commentId)
                .eq(StoryComment::getUserId, UserHolder.getUser().getId())
                .remove();
        if (!removed) {
            return Result.fail("评论状态已变化，请刷新后重试");
        }
        storyMapper.update(null, new UpdateWrapper<Story>()
                .setSql("comments = GREATEST(COALESCE(comments, 0) - 1, 0)")
                .eq("id", existing.getStoryId()));
        return Result.ok();
    }

    private boolean belongsToStory(Long commentId, Long storyId) {
        return lambdaQuery()
                .eq(StoryComment::getId, commentId)
                .eq(StoryComment::getStoryId, storyId)
                .eq(StoryComment::getStatus, 0)
                .count() > 0;
    }

    private void enrichAuthors(List<StoryComment> comments) {
        if (comments == null || comments.isEmpty()) {
            return;
        }
        List<Long> userIds = comments.stream().map(StoryComment::getUserId).distinct().collect(Collectors.toList());
        Map<Long, User> users = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        for (StoryComment comment : comments) {
            User user = users.get(comment.getUserId());
            if (user != null) {
                comment.setAuthorName(user.getNickName());
                comment.setAuthorIcon(user.getIcon());
            }
        }
    }
}
