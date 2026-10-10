package com.citypass.story;

import com.citypass.config.StoryFileProperties;
import com.citypass.reliable.ReliableTaskRepository;
import com.citypass.storage.ObjectStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.*;
import static com.citypass.story.StoryTransactions.*;

@Slf4j
@Component
public class StoryFileCleanup {

    private final StoryTransactions storyTransactions;
    private final StoryDraftService storyDraftService;
    private final StoryFileProperties storyFileProperties;
    private final ObjectStorage objectStorage;

    public StoryFileCleanup(
            StoryTransactions storyTransactions,
            StoryDraftService storyDraftService,
            StoryFileProperties storyFileProperties,
            ObjectStorage objectStorage) {
        this.storyTransactions = storyTransactions;
        this.storyDraftService = storyDraftService;
        this.storyFileProperties = storyFileProperties;
        this.objectStorage = objectStorage;
    }

    @Scheduled(fixedDelayString = "${story-files.sweep-delay-ms:5000}")
    public void sweep() {
        try {
            for (Long id :
                    storyTransactions.jdbcTemplate.queryForList(
                            "SELECT id FROM tb_story WHERE status='DRAFT' AND draft_expires_at<=NOW() ORDER BY id LIMIT ?",
                            Long.class,
                            storyFileProperties.getSweepBatch())) {
                storyTransactions.inTransaction(
                        () -> {
                            Map<String, Object> story = storyTransactions.story(id, true);
                            if ("DRAFT".equals(story.get("status"))
                                    && !time(story, "draft_expires_at")
                                            .isAfter(LocalDateTime.now())) {
                                storyDraftService.deleteLocked(story);
                            }

                            return null;
                        });
            }

            for (Long id :
                    storyTransactions.jdbcTemplate.queryForList(
                            "SELECT a.id FROM tb_story_attachment a WHERE a.state IN ('PENDING','READY') AND a.expires_at<=NOW() AND NOT EXISTS(SELECT 1 FROM tb_story_attachment_ref r WHERE r.attachment_id=a.id AND r.active=1) ORDER BY a.id LIMIT ?",
                            Long.class,
                            storyFileProperties.getSweepBatch())) {
                storyTransactions.inTransaction(
                        () -> {
                            Map<String, Object> existingAttachment =
                                    storyTransactions.attachment(id, false);
                            storyTransactions.story(number(existingAttachment, "story_id"), true);
                            Map<String, Object> attachment = storyTransactions.attachment(id, true);
                            long activeReferenceCount =
                                    storyTransactions.jdbcTemplate.queryForObject(
                                            "SELECT COUNT(*) FROM tb_story_attachment_ref WHERE attachment_id=? AND active=1",
                                            Long.class,
                                            id);
                            if (activeReferenceCount == 0
                                    && ("PENDING".equals(attachment.get("state"))
                                            || "READY".equals(attachment.get("state")))
                                    && !time(attachment, "expires_at")
                                            .isAfter(LocalDateTime.now())) {
                                storyTransactions.retireAttachment(attachment);
                            }

                            return null;
                        });
            }
            // Sweep tombstones too. A PUT begun before expiry can finish after the first deletion.
            for (Long id :
                    storyTransactions.jdbcTemplate.queryForList(
                            "SELECT o.id FROM tb_story_file_object o JOIN tb_story_attachment a ON a.id=o.attachment_id LEFT JOIN tb_reliable_task task ON task.id=o.cleanup_task_id WHERE o.cleanup_after<=NOW() AND (o.last_scheduled_at IS NULL OR o.last_scheduled_at<=DATE_SUB(NOW(),INTERVAL ? SECOND)) AND (task.id IS NULL OR task.status='DONE') AND NOT(a.state='READY' AND a.final_key=o.object_key) ORDER BY o.last_scheduled_at,o.id LIMIT ?",
                            Long.class,
                            storyFileProperties.getTombstoneRescanSeconds(),
                            storyFileProperties.getSweepBatch())) {
                storyTransactions.inTransaction(
                        () -> {
                            List<Map<String, Object>> rows =
                                    storyTransactions.jdbcTemplate.queryForList(
                                            "SELECT * FROM tb_story_file_object WHERE id=?", id);
                            if (rows.isEmpty()) {
                                return null;
                            }

                            Map<String, Object> fileObject = rows.get(0);
                            Map<String, Object> existingAttachment =
                                    storyTransactions.attachment(
                                            number(fileObject, "attachment_id"), false);
                            storyTransactions.story(number(existingAttachment, "story_id"), true);
                            Map<String, Object> attachment =
                                    storyTransactions.attachment(
                                            number(fileObject, "attachment_id"), true);
                            if (!canDelete(fileObject, attachment)) {
                                return null;
                            }

                            List<Map<String, Object>> lockedFileObjects =
                                    storyTransactions.jdbcTemplate.queryForList(
                                            "SELECT * FROM tb_story_file_object WHERE id=? FOR UPDATE",
                                            id);
                            fileObject = lockedFileObjects.get(0);
                            // Keep the existing retry/dead-letter task as the sole owner of this
                            // object.
                            // Without this fence, an outage creates a new task on every tombstone
                            // sweep.
                            if (fileObject.get("cleanup_task_id") != null) {
                                List<String> statuses =
                                        storyTransactions.jdbcTemplate.queryForList(
                                                "SELECT status FROM tb_reliable_task WHERE id=?",
                                                String.class,
                                                fileObject.get("cleanup_task_id"));
                                if (!statuses.isEmpty() && !"DONE".equals(statuses.get(0))) {
                                    return null;
                                }
                            }

                            if (fileObject.get("last_scheduled_at") != null
                                    && time(fileObject, "last_scheduled_at")
                                            .plusSeconds(
                                                    storyFileProperties.getTombstoneRescanSeconds())
                                            .isAfter(LocalDateTime.now())) {
                                return null;
                            }

                            String cleanupRound = UUID.randomUUID().toString();
                            String key = "delete-story-file:" + id + ":" + cleanupRound;
                            storyTransactions.reliableTaskRepository.enqueue(
                                    ReliableTaskRepository.DELETE_STORY_FILE,
                                    key,
                                    String.valueOf(id));
                            Long taskId =
                                    storyTransactions.jdbcTemplate.queryForObject(
                                            "SELECT id FROM tb_reliable_task WHERE biz_key=?",
                                            Long.class,
                                            key);
                            storyTransactions.jdbcTemplate.update(
                                    "UPDATE tb_story_file_object SET last_scheduled_at=NOW(),cleanup_task_id=? WHERE id=?",
                                    taskId,
                                    id);
                            return null;
                        });
            }
        } catch (Exception exception) {
            log.warn("笔记文件清理扫描失败，将在下一轮重试", exception);
        }
    }

    boolean canDelete(Map<String, Object> fileObject, Map<String, Object> attachment) {
        if (time(fileObject, "cleanup_after").isAfter(LocalDateTime.now())) {
            return false;
        }

        if ("READY".equals(attachment.get("state"))
                && fileObject.get("object_key").equals(attachment.get("final_key"))) {
            return false;
        }

        if ("FINAL".equals(fileObject.get("kind"))
                && "PENDING".equals(attachment.get("state"))
                && Objects.equals(fileObject.get("attempt_id"), attachment.get("current_attempt"))
                && attachment.get("confirm_lease_until") != null
                && time(attachment, "confirm_lease_until").isAfter(LocalDateTime.now())) {
            return false;
        }

        return true;
    }

    public void execute(String payload) {
        long id = Long.parseLong(payload);
        String key =
                storyTransactions.inTransaction(
                        () -> {
                            List<Map<String, Object>> rows =
                                    storyTransactions.jdbcTemplate.queryForList(
                                            "SELECT * FROM tb_story_file_object WHERE id=?", id);
                            if (rows.isEmpty()) {
                                return null;
                            }

                            Map<String, Object> fileObject = rows.get(0);
                            Map<String, Object> existingAttachment =
                                    storyTransactions.attachment(
                                            number(fileObject, "attachment_id"), false);
                            storyTransactions.story(number(existingAttachment, "story_id"), true);
                            Map<String, Object> attachment =
                                    storyTransactions.attachment(
                                            number(fileObject, "attachment_id"), true);
                            if (!canDelete(fileObject, attachment)) {
                                return null;
                            }
                            // The candidate FINAL attempt cannot be adopted after its lease ends.
                            // STAGING can never become a published object. DELETING cannot be
                            // rebound.
                            return (String) fileObject.get("object_key");
                        });
        if (key == null) {
            return;
        }

        try {
            objectStorage.delete(key);
        } catch (Exception exception) {
            throw new IllegalStateException("对象存储删除失败，请通过可靠任务重试");
        }

        storyTransactions.inTransaction(
                () -> {
                    Map<String, Object> fileObject =
                            storyTransactions.jdbcTemplate.queryForMap(
                                    "SELECT * FROM tb_story_file_object WHERE id=?", id);
                    long attachmentId = number(fileObject, "attachment_id");
                    Map<String, Object> existingAttachment =
                            storyTransactions.attachment(attachmentId, false);
                    storyTransactions.story(number(existingAttachment, "story_id"), true);
                    Map<String, Object> attachment =
                            storyTransactions.attachment(attachmentId, true);
                    storyTransactions.jdbcTemplate.update(
                            "UPDATE tb_story_file_object SET state='DELETED',last_deleted_at=NOW() WHERE id=?",
                            id);
                    if ("DELETING".equals(attachment.get("state"))) {
                        long remainingObjectCount =
                                storyTransactions.jdbcTemplate.queryForObject(
                                        "SELECT COUNT(*) FROM tb_story_file_object WHERE attachment_id=? AND state<>'DELETED'",
                                        Long.class,
                                        attachmentId);
                        if (remainingObjectCount == 0) {
                            storyTransactions.jdbcTemplate.update(
                                    "UPDATE tb_story_attachment SET state='DELETED' WHERE id=? AND state='DELETING'",
                                    attachmentId);
                        }
                    }

                    return null;
                });
    }
}
