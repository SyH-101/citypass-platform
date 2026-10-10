package com.citypass.story;

import com.citypass.config.StoryFileProperties;
import com.citypass.reliable.ReliableTaskRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;

/**
 * Only short DB operations here. Storage/Redis calls belong outside these transactions.
 */
@Component
public class StoryTransactions {

    final JdbcTemplate jdbcTemplate;
    final TransactionTemplate transactionTemplate;
    final StoryFileProperties storyFileProperties;
    final ReliableTaskRepository reliableTaskRepository;

    public StoryTransactions(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            StoryFileProperties storyFileProperties,
            ReliableTaskRepository reliableTaskRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.storyFileProperties = storyFileProperties;
        this.reliableTaskRepository = reliableTaskRepository;
    }

    public <T> T inTransaction(Supplier<T> action) {
        return transactionTemplate.execute(transactionStatus -> action.get());
    }

    public Map<String, Object> story(long id, boolean lock) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT * FROM tb_story WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) {
            throw StoryProblem.missing();
        }

        return rows.get(0);
    }

    public Map<String, Object> ownedStory(long id, long userId, boolean lock) {
        Map<String, Object> story = story(id, lock);
        if (number(story, "user_id") != userId) {
            throw StoryProblem.missing();
        }

        return story;
    }

    public Map<String, Object> attachment(long id, boolean lock) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT * FROM tb_story_attachment WHERE id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        id);
        if (rows.isEmpty()) {
            throw StoryProblem.missing();
        }

        return rows.get(0);
    }

    public void checkLive(Map<String, Object> story) {
        if ("DELETED".equals(story.get("status"))) {
            throw StoryProblem.missing();
        }

        if ("DRAFT".equals(story.get("status"))
                && time(story, "draft_expires_at").isBefore(LocalDateTime.now())) {
            throw StoryProblem.conflict("草稿已过期，请新建草稿");
        }
    }

    public long insert(String sql, Object... parameters) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(
                connection -> {
                    PreparedStatement statement =
                            connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                    for (int i = 0; i < parameters.length; i++) {
                        statement.setObject(
                                i + 1,
                                parameters[i] instanceof LocalDateTime
                                        ? Timestamp.valueOf((LocalDateTime) parameters[i])
                                        : parameters[i]);
                    }

                    return statement;
                },
                keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public static long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    public static LocalDateTime time(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime();
        }

        return (LocalDateTime) value;
    }

    public void queueFeed(long id, long version) {
        reliableTaskRepository.enqueue(
                ReliableTaskRepository.STORY_FEED,
                "story-feed:" + id + ":" + version,
                id + ":" + version);
    }

    /**
     * Story row must already be locked, then attachment row, before claiming deletion.
     */
    public void retireAttachment(Map<String, Object> attachment) {
        long id = number(attachment, "id");
        LocalDateTime cleanupAfter =
                LocalDateTime.now()
                        .plusSeconds(
                                Math.max(
                                        storyFileProperties.getReadSeconds(),
                                        storyFileProperties.getCleanupGraceSeconds()));
        LocalDateTime uploadSafeAfter =
                time(attachment, "upload_url_expires_at")
                        .plusSeconds(storyFileProperties.getCleanupGraceSeconds());
        if (uploadSafeAfter.isAfter(cleanupAfter)) {
            cleanupAfter = uploadSafeAfter;
        }

        Object confirmationLease = attachment.get("confirm_lease_until");
        if (confirmationLease != null) {
            LocalDateTime leaseSafeAfter =
                    time(attachment, "confirm_lease_until")
                            .plusSeconds(storyFileProperties.getCleanupGraceSeconds());
            if (leaseSafeAfter.isAfter(cleanupAfter)) {
                cleanupAfter = leaseSafeAfter;
            }
        }

        jdbcTemplate.update(
                "UPDATE tb_story_attachment SET state='DELETING',delete_after=? WHERE id=? AND state IN ('PENDING','READY')",
                cleanupAfter,
                id);
        jdbcTemplate.update(
                "UPDATE tb_story_file_object SET cleanup_after=GREATEST(cleanup_after,?) WHERE attachment_id=?",
                cleanupAfter,
                id);
    }
}
