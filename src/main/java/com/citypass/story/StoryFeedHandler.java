package com.citypass.story;

import com.citypass.config.StoryFileProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.*;
import static com.citypass.story.StoryTransactions.*;

@Component
public class StoryFeedHandler {
    private static final DefaultRedisScript<Long> SCRIPT=new DefaultRedisScript<>();
    static { SCRIPT.setLocation(new ClassPathResource("story-feed.lua")); SCRIPT.setResultType(Long.class); }
    private final StoryTransactions t;
    private final StoryFileProperties p;
    private final StringRedisTemplate redis;
    public StoryFeedHandler(StoryTransactions t,StoryFileProperties p,StringRedisTemplate redis) { this.t=t; this.p=p; this.redis=redis; }

    public void execute(String eventKey) {
        String[] parts=eventKey.split(":");
        long storyId=Long.parseLong(parts[0]);
        t.db.update("INSERT IGNORE INTO tb_story_feed_progress(event_key) VALUES(?)",eventKey);
        long after=t.db.queryForObject("SELECT last_subscription_id FROM tb_story_feed_progress WHERE event_key=?",Long.class,eventKey);
        while(true) {
            Map<String,Object> story=t.story(storyId,false);
            List<Map<String,Object>> batch=t.db.queryForList("SELECT id,user_id FROM tb_subscription WHERE target_user_id=? AND id>? ORDER BY id LIMIT ?",number(story,"user_id"),after,p.getFeedBatch());
            if(batch.isEmpty()) return;
            String status=(String)story.get("status");
            long version=number(story,"version");
            long score=story.get("publish_time")==null?0:time(story,"publish_time").atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            for(Map<String,Object> subscriber:batch) {
                long user=number(subscriber,"user_id");
                Long applied=redis.execute(SCRIPT,Arrays.asList("feed:"+user,"feed:story-versions:"+user),
                        String.valueOf(storyId),String.valueOf(version),status,String.valueOf(score));
                if(applied==null) throw new IllegalStateException("Feed 更新未被 Redis 确认");
                after=number(subscriber,"id");
            }
            // If the worker dies here, repeating the batch applies the same member/score/version.
            t.db.update("UPDATE tb_story_feed_progress SET last_subscription_id=GREATEST(last_subscription_id,?) WHERE event_key=?",after,eventKey);
        }
    }
}
