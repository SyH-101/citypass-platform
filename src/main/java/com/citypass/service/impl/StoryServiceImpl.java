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
import com.citypass.story.StoryFileService;
import com.citypass.story.StoryDraftService;
import com.citypass.story.StoryProblem;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private StoryFileService storyFileService;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public Result queryHotStory(Integer current) {
        // 根据用户查询
        Page<Story> page = query()
                .eq("status", "PUBLISHED")
                .orderByDesc("liked")
                .orderByDesc("id")
                .page(new Page<>(Math.max(1,current), SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Story> records = page.getRecords();
        // 查询用户
        records.forEach(story -> {
            this.queryStoryUser(story);
            this.isStoryLiked(story);
            this.decorateImages(story);
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
        checkReadable(story);
        // 2.查询story有关的用户
        queryStoryUser(story);
        // 3.查询story是否被点赞
        isStoryLiked(story);
        decorateImages(story);
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
        requirePublished(id);
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.判断当前登录用户是否已经点赞
        String key = STORY_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 3.如果未点赞，可以点赞
            // 3.1.数据库点赞数 + 1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).eq("status","PUBLISHED").update();
            // 3.2.保存用户到Redis的set集合  zadd key value score
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 4.如果已点赞，取消点赞
            // 4.1.数据库点赞数 -1
            boolean isSuccess = update().setSql("liked = GREATEST(liked - 1,0)").eq("id", id).eq("status","PUBLISHED").update();
            // 4.2.把用户从Redis的set集合移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryStoryLikes(Long id) {
        requirePublished(id);
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
    public Result queryByAuthor(Long userId,Integer current,boolean own) {
        if(userId==null) throw new StoryProblem(400,"作者编号不能为空");
        if(own && (UserHolder.getUser()==null || !userId.equals(UserHolder.getUser().getId()))) throw StoryProblem.missing();
        com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper<Story> q=query().eq("user_id",userId);
        if(own) q.ne("status","DELETED").and(w -> w.eq("status","PUBLISHED").or().gt("draft_expires_at",java.time.LocalDateTime.now()));
        else q.eq("status","PUBLISHED");
        List<Story> rows=q.orderByDesc("id").page(new Page<>(Math.max(1,current==null?1:current),SystemConstants.MAX_PAGE_SIZE)).getRecords();
        for(Story story:rows) { queryStoryUser(story); isStoryLiked(story); decorateImages(story); }
        return Result.ok(rows);
    }

    @Override
    public Result queryStoriesOfSubscriptions(Long max,Integer offset) {
        if(max==null || max<0 || offset==null || offset<0) throw new StoryProblem(400,"信息流游标非法");
        String key=FEED_KEY+UserHolder.getUser().getId();
        List<Story> rows=new ArrayList<>();
        long cursor=max;
        int consumed=offset;
        // Bound scanned pages. The returned cursor advances even over hidden/deleted rows.
        for(int batch=0;batch<20 && rows.size()<2;batch++) {
            Set<ZSetOperations.TypedTuple<String>> hits=stringRedisTemplate.opsForZSet()
                    .reverseRangeByScoreWithScores(key,0,cursor,consumed,2-rows.size());
            if(hits==null || hits.isEmpty()) break;
            for(ZSetOperations.TypedTuple<String> hit:hits) {
                long score=hit.getScore().longValue();
                if(score==cursor) consumed++; else { cursor=score; consumed=1; }
                Story story=getById(Long.valueOf(hit.getValue()));
                if(story!=null && "PUBLISHED".equals(story.getStatus())) {
                    queryStoryUser(story); isStoryLiked(story); decorateImages(story); rows.add(story);
                }
            }
        }
        ScrollResult result=new ScrollResult(); result.setList(rows); result.setMinTime(cursor); result.setOffset(consumed);
        return Result.ok(result);
    }

    private void requirePublished(Long id) {
        Story story=getById(id);
        if(story==null || !"PUBLISHED".equals(story.getStatus())) throw StoryProblem.missing();
    }
    private void checkReadable(Story story) {
        Long current=UserHolder.getUser()==null?null:UserHolder.getUser().getId();
        if("DELETED".equals(story.getStatus()) || (!"PUBLISHED".equals(story.getStatus()) && !story.getUserId().equals(current))) throw StoryProblem.missing();
        if("DRAFT".equals(story.getStatus()) && !story.getDraftExpiresAt().isAfter(java.time.LocalDateTime.now())) throw StoryProblem.missing();
    }
    private void decorateImages(Story story) {
        List<Map<String,Object>> attachments=new ArrayList<>();
        List<String> urls=new ArrayList<>();
        List<String> legacy=StoryDraftService.legacy(story.getImages());
        for(int i=0;i<legacy.size();i++) urls.add("/stories/"+story.getId()+"/legacy-images/"+i);
        for(Map<String,Object> row:jdbcTemplate.queryForList("SELECT a.* FROM tb_story_attachment_ref r JOIN tb_story_attachment a ON a.id=r.attachment_id WHERE r.story_id=? AND r.active=1 AND a.state='READY' ORDER BY r.position",story.getId())) {
            Map<String,Object> info=StoryFileService.publicInfo(row);
            String url=storyFileService.readUrl(story.getId(),((Number)row.get("id")).longValue(),UserHolder.getUser()==null?null:UserHolder.getUser().getId());
            info.put("url",url); attachments.add(info); urls.add(url);
        }
        story.setAttachments(attachments).setImages(String.join(",",urls));
        story.setClientKey(null);
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
