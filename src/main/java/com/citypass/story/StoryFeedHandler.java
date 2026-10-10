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

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();

    static {
        SCRIPT.setLocation(new ClassPathResource("story-feed.lua"));
        SCRIPT.setResultType(Long.class);
    }

    private final StoryTransactions storyTransactions;
    private final StoryFileProperties storyFileProperties;
    private final StringRedisTemplate stringRedisTemplate;

    public StoryFeedHandler(
            StoryTransactions storyTransactions,
            StoryFileProperties storyFileProperties,
            StringRedisTemplate stringRedisTemplate) {
        this.storyTransactions = storyTransactions;
        this.storyFileProperties = storyFileProperties;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void execute(String eventKey) {
        String[] eventKeyParts = eventKey.split(":");
        long storyId = Long.parseLong(eventKeyParts[0]);
        storyTransactions.jdbcTemplate.update(
                "INSERT IGNORE INTO tb_story_feed_progress(event_key) VALUES(?)", eventKey);
        long lastSubscriptionId =
                storyTransactions.jdbcTemplate.queryForObject(
                        "SELECT last_subscription_id FROM tb_story_feed_progress WHERE event_key=?",
                        Long.class,
                        eventKey);
        while (true) {
            Map<String, Object> story = storyTransactions.story(storyId, false);
            List<Map<String, Object>> subscribers =
                    storyTransactions.jdbcTemplate.queryForList(
                            "SELECT id,user_id FROM tb_subscription WHERE target_user_id=? AND id>? ORDER BY id LIMIT ?",
                            number(story, "user_id"),
                            lastSubscriptionId,
                            storyFileProperties.getFeedBatch());
            if (subscribers.isEmpty()) {
                return;
            }

            String status = (String) story.get("status");
            long version = number(story, "version");
            long score =
                    story.get("publish_time") == null
                            ? 0
                            : time(story, "publish_time")
                                    .atZone(ZoneId.systemDefault())
                                    .toInstant()
                                    .toEpochMilli();
            for (Map<String, Object> subscriber : subscribers) {
                long subscriberUserId = number(subscriber, "user_id");
                Long appliedVersion =
                        stringRedisTemplate.execute(
                                SCRIPT,
                                Arrays.asList(
                                        "feed:" + subscriberUserId,
                                        "feed:story-versions:" + subscriberUserId),
                                String.valueOf(storyId),
                                String.valueOf(version),
                                status,
                                String.valueOf(score));
                if (appliedVersion == null) {
                    throw new IllegalStateException("Feed 更新未被 Redis 确认");
                }

                lastSubscriptionId = number(subscriber, "id");
            }
            // If the worker dies here, repeating the batch applies the same member/score/version.
            storyTransactions.jdbcTemplate.update(
                    "UPDATE tb_story_feed_progress SET last_subscription_id=GREATEST(last_subscription_id,?) WHERE event_key=?",
                    lastSubscriptionId,
                    eventKey);
        }
    }
}
