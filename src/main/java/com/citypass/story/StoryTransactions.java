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

/** Only short DB operations here. Storage/Redis calls belong outside these transactions. */
@Component
public class StoryTransactions {
    final JdbcTemplate db;
    final TransactionTemplate tx;
    final StoryFileProperties p;
    final ReliableTaskRepository tasks;

    public StoryTransactions(JdbcTemplate db, TransactionTemplate tx, StoryFileProperties p,
                             ReliableTaskRepository tasks) {
        this.db = db; this.tx = tx; this.p = p; this.tasks = tasks;
    }
    public <T> T inTransaction(Supplier<T> action) { return tx.execute(s -> action.get()); }
    public Map<String,Object> story(long id, boolean lock) {
        List<Map<String,Object>> rows = db.queryForList("SELECT * FROM tb_story WHERE id=?" +
                (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw StoryProblem.missing();
        return rows.get(0);
    }
    public Map<String,Object> ownedStory(long id, long user, boolean lock) {
        Map<String,Object> story = story(id, lock);
        if (number(story,"user_id") != user) throw StoryProblem.missing();
        return story;
    }
    public Map<String,Object> attachment(long id, boolean lock) {
        List<Map<String,Object>> rows = db.queryForList("SELECT * FROM tb_story_attachment WHERE id=?" +
                (lock ? " FOR UPDATE" : ""), id);
        if (rows.isEmpty()) throw StoryProblem.missing();
        return rows.get(0);
    }
    public void checkLive(Map<String,Object> story) {
        if ("DELETED".equals(story.get("status"))) throw StoryProblem.missing();
        if ("DRAFT".equals(story.get("status")) && time(story,"draft_expires_at").isBefore(LocalDateTime.now())) {
            throw StoryProblem.conflict("草稿已过期，请新建草稿");
        }
    }
    public long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        db.update(c -> {
            PreparedStatement statement = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i=0; i<args.length; i++) statement.setObject(i+1,
                    args[i] instanceof LocalDateTime ? Timestamp.valueOf((LocalDateTime)args[i]) : args[i]);
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
    public static long number(Map<String,Object> row, String field) { return ((Number)row.get(field)).longValue(); }
    public static LocalDateTime time(Map<String,Object> row, String field) {
        Object value=row.get(field);
        if (value instanceof Timestamp) return ((Timestamp)value).toLocalDateTime();
        return (LocalDateTime)value;
    }
    public void queueFeed(long id, long version) {
        tasks.enqueue(ReliableTaskRepository.STORY_FEED,
                "story-feed:"+id+":"+version, id+":"+version);
    }
    /** Story row must already be locked, then attachment row, before claiming deletion. */
    public void retireAttachment(Map<String,Object> attachment) {
        long id=number(attachment,"id");
        LocalDateTime after=LocalDateTime.now().plusSeconds(Math.max(p.getReadSeconds(),p.getCleanupGraceSeconds()));
        LocalDateTime uploadSafe=time(attachment,"upload_url_expires_at").plusSeconds(p.getCleanupGraceSeconds());
        if (uploadSafe.isAfter(after)) after=uploadSafe;
        Object lease=attachment.get("confirm_lease_until");
        if (lease!=null) {
            LocalDateTime safe=time(attachment,"confirm_lease_until").plusSeconds(p.getCleanupGraceSeconds());
            if (safe.isAfter(after)) after=safe;
        }
        db.update("UPDATE tb_story_attachment SET state='DELETING',delete_after=? WHERE id=? AND state IN ('PENDING','READY')",after,id);
        db.update("UPDATE tb_story_file_object SET cleanup_after=GREATEST(cleanup_after,?) WHERE attachment_id=?",after,id);
    }
}
