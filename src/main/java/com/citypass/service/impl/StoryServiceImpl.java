package com.citypass.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.citypass.dto.Result;
import com.citypass.dto.ScrollResult;
import com.citypass.dto.UserDTO;
import com.citypass.entity.Story;
import com.citypass.entity.Subscription;
import com.citypass.entity.User;
import com.citypass.mapper.StoryMapper;
import com.citypass.service.IStoryService;
import com.citypass.service.ISubscriptionService;
import com.citypass.service.IUserService;
import com.citypass.utils.SystemConstants;
import com.citypass.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.citypass.utils.RedisConstants.STORY_LIKED_KEY;
import static com.citypass.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @since 2021-12-22
 */
@Service
public class StoryServiceImpl extends ServiceImpl<StoryMapper, Story> implements IStoryService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ISubscriptionService subscriptionService;

    @Override
    public Result queryHotStory(Integer current) {
        // 根据用户查询
        Page<Story> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Story> records = page.getRecords();
        // 查询用户
        records.forEach(story -> {
            this.queryStoryUser(story);
            this.isStoryLiked(story);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryStoryById(Long id) {
        // 1.查询story
        Story story = getById(id);
        if (story == null) {
            return Result.fail("笔记不存在！");
        }
        // 2.查询story有关的用户
        queryStoryUser(story);
        // 3.查询story是否被点赞
        isStoryLiked(story);
        return Result.ok(story);
    }

    private void isStoryLiked(Story story) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询是否点赞
            return;
        }
        Long userId = user.getId();
        // 2.判断当前登录用户是否已经点赞
        String key = "story:liked:" + story.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        story.setIsLike(score != null);
    }

    @Override
    public Result likeStory(Long id) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.判断当前登录用户是否已经点赞
        String key = STORY_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 3.如果未点赞，可以点赞
            // 3.1.数据库点赞数 + 1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 3.2.保存用户到Redis的set集合  zadd key value score
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 4.如果已点赞，取消点赞
            // 4.1.数据库点赞数 -1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 4.2.把用户从Redis的set集合移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryStoryLikes(Long id) {
        String key = STORY_LIKED_KEY + id;
        // 1.查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 3.根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 4.返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveStory(Story story) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        story.setUserId(user.getId());
        // 2.保存活动笔记
        boolean isSuccess = save(story);
        if(!isSuccess){
            return Result.fail("新增笔记失败!");
        }
        // 查询订阅作者的用户，并把动态编号推送到各自的时间线。
        List<Subscription> subscriptions = subscriptionService.query()
                .eq("target_user_id", user.getId()).list();
        for (Subscription subscription : subscriptions) {
            Long userId = subscription.getUserId();
            // 4.2.推送
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, story.getId().toString(), System.currentTimeMillis());
        }
        // 5.返回id
        return Result.ok(story.getId());
    }

    @Override
    public Result queryStoriesOfSubscriptions(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 3.非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 4.解析数据：storyId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0; // 2
        int os = 1; // 2
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 5 4 4 2 2
            // 4.1.获取id
            ids.add(Long.valueOf(tuple.getValue()));
            // 4.2.获取分数(时间戳）
            long time = tuple.getScore().longValue();
            if(time == minTime){
                os++;
            }else{
                minTime = time;
                os = 1;
            }
        }

        // 5.根据id查询story
        String idStr = StrUtil.join(",", ids);
        List<Story> storys = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Story story : storys) {
            // 5.1.查询story有关的用户
            queryStoryUser(story);
            // 5.2.查询story是否被点赞
            isStoryLiked(story);
        }

        // 6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(storys);
        r.setOffset(os);
        r.setMinTime(minTime);

        return Result.ok(r);
    }

    private void queryStoryUser(Story story) {
        Long userId = story.getUserId();
        User user = userService.getById(userId);
        if (user == null) {
            return;
        }
        story.setName(user.getNickName());
        story.setIcon(user.getIcon());
    }
}
