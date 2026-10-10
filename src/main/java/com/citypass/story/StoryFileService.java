package com.citypass.story;

import com.citypass.config.StoryFileProperties;
import com.citypass.storage.ObjectStorage;
import org.springframework.stereotype.Service;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import static com.citypass.story.StoryTransactions.*;

@Service
public class StoryFileService {

    private final StoryTransactions storyTransactions;
    private final StoryFileProperties storyFileProperties;
    private final ObjectStorage objectStorage;
    private final ImageInspector imageInspector;
    private final Semaphore imageProcessingPermits;

    public StoryFileService(
            StoryTransactions storyTransactions,
            StoryFileProperties storyFileProperties,
            ObjectStorage objectStorage,
            ImageInspector imageInspector) {
        this.storyTransactions = storyTransactions;
        this.storyFileProperties = storyFileProperties;
        this.objectStorage = objectStorage;
        this.imageInspector = imageInspector;
        this.imageProcessingPermits =
                new Semaphore(Math.max(1, storyFileProperties.getIoConcurrency()));
    }

    public Map<String, Object> requestUpload(long storyId, long userId) {
        Map<String, Object> attachment =
                storyTransactions.inTransaction(
                        () -> {
                            storyTransactions.jdbcTemplate.queryForObject(
                                    "SELECT id FROM tb_user WHERE id=? FOR UPDATE",
                                    Long.class,
                                    userId);
                            Map<String, Object> story =
                                    storyTransactions.ownedStory(storyId, userId, true);
                            storyTransactions.checkLive(story);
                            long pendingAttachmentCount =
                                    storyTransactions.jdbcTemplate.queryForObject(
                                            "SELECT COUNT(*) FROM tb_story_attachment a WHERE user_id=? AND state='PENDING'",
                                            Long.class,
                                            userId);
                            if (pendingAttachmentCount >= storyFileProperties.getPendingQuota()) {
                                throw new StoryProblem(429, "待上传额度已满");
                            }

                            long attachmentCount =
                                    storyTransactions.jdbcTemplate.queryForObject(
                                            "SELECT COUNT(*) FROM tb_story_attachment WHERE story_id=? AND state IN ('PENDING','READY')",
                                            Long.class,
                                            storyId);
                            if (attachmentCount >= storyFileProperties.getMaxImages()) {
                                throw new StoryProblem(422, "每篇图片额度已满，请移除不需要的附件");
                            }

                            String stagingKey =
                                    "stories/staging/" + userId + "/" + UUID.randomUUID();
                            LocalDateTime uploadDeadline =
                                    LocalDateTime.now()
                                            .plusSeconds(storyFileProperties.getUploadSeconds());
                            long id =
                                    storyTransactions.insert(
                                            "INSERT INTO tb_story_attachment(user_id,story_id,staging_key,expires_at,upload_url_expires_at) VALUES(?,?,?,?,?)",
                                            userId,
                                            storyId,
                                            stagingKey,
                                            uploadDeadline,
                                            uploadDeadline);
                            storyTransactions.jdbcTemplate.update(
                                    "INSERT INTO tb_story_file_object(attachment_id,object_key,kind,cleanup_after) VALUES(?,?,'STAGING',?)",
                                    id,
                                    stagingKey,
                                    uploadDeadline.plusSeconds(
                                            storyFileProperties.getCleanupGraceSeconds()));
                            return storyTransactions.attachment(id, false);
                        });
        try {
            Map<String, Object> result = publicInfo(attachment);
            int remainingSeconds =
                    (int)
                            Duration.between(
                                            LocalDateTime.now(),
                                            time(attachment, "upload_url_expires_at"))
                                    .getSeconds();
            if (remainingSeconds < 1) {
                throw StoryProblem.conflict("上传凭证申请已超时，请移除附件后重新申请");
            }
            // Signing must not extend the durable latest-expiry boundary used by cleanup.
            result.put(
                    "uploadUrl",
                    objectStorage.signPut(
                            (String) attachment.get("staging_key"), remainingSeconds));
            result.put("method", "PUT");
            result.put("expiresAt", attachment.get("upload_url_expires_at"));
            result.put("maxBytes", storyFileProperties.getMaxBytes());
            return result;
        } catch (Exception exception) {
            throw new StoryProblem(503, "上传凭证生成失败，可移除此附件后重新申请");
        }
    }

    public Map<String, Object> confirm(long id, long userId) {
        if (!imageProcessingPermits.tryAcquire()) {
            throw new StoryProblem(503, "图片处理繁忙，请稍后重试");
        }

        try {
            return confirmWithPermit(id, userId);
        } finally {
            imageProcessingPermits.release();
        }
    }

    private Map<String, Object> confirmWithPermit(long id, long userId) {
        Map<String, Object> claim =
                storyTransactions.inTransaction(
                        () -> {
                            Map<String, Object> existingAttachment =
                                    storyTransactions.attachment(id, false);
                            if (number(existingAttachment, "user_id") != userId) {
                                throw StoryProblem.missing();
                            }

                            Map<String, Object> story =
                                    storyTransactions.ownedStory(
                                            number(existingAttachment, "story_id"), userId, true);
                            storyTransactions.checkLive(story);
                            Map<String, Object> attachment = storyTransactions.attachment(id, true);
                            if ("READY".equals(attachment.get("state"))) {
                                return attachment;
                            }

                            if (!"PENDING".equals(attachment.get("state"))) {
                                throw StoryProblem.conflict("附件已进入清理，不能确认");
                            }

                            if (!time(attachment, "expires_at").isAfter(LocalDateTime.now())) {
                                throw StoryProblem.conflict("上传会话已过期");
                            }

                            if (attachment.get("confirm_lease_until") != null
                                    && time(attachment, "confirm_lease_until")
                                            .isAfter(LocalDateTime.now())) {
                                throw StoryProblem.conflict("附件正在确认，请稍后重试");
                            }

                            String confirmationAttempt = UUID.randomUUID().toString();
                            String key = "stories/final/" + userId + "/" + confirmationAttempt;
                            LocalDateTime confirmationLeaseUntil =
                                    LocalDateTime.now()
                                            .plusSeconds(
                                                    storyFileProperties
                                                            .getConfirmationLeaseSeconds());
                            // Attempt and destination are persisted before external I/O, including
                            // crash windows.
                            storyTransactions.jdbcTemplate.update(
                                    "INSERT INTO tb_story_file_object(attachment_id,object_key,attempt_id,kind,cleanup_after) VALUES(?,?,?,'FINAL',?)",
                                    id,
                                    key,
                                    confirmationAttempt,
                                    confirmationLeaseUntil.plusSeconds(
                                            storyFileProperties.getCleanupGraceSeconds()));
                            storyTransactions.jdbcTemplate.update(
                                    "UPDATE tb_story_attachment SET current_attempt=?,confirm_lease_until=? WHERE id=? AND state='PENDING'",
                                    confirmationAttempt,
                                    confirmationLeaseUntil,
                                    id);
                            attachment.put("attempt", confirmationAttempt);
                            attachment.put("candidate_key", key);
                            return attachment;
                        });
        if ("READY".equals(claim.get("state"))) {
            return publicInfo(claim);
        }

        String confirmationAttempt = (String) claim.get("attempt"),
                finalObjectKey = (String) claim.get("candidate_key");
        try {
            long stagingSize = objectStorage.size((String) claim.get("staging_key"));
            if (stagingSize <= 0 || stagingSize > storyFileProperties.getMaxBytes()) {
                throw new StoryProblem(422, "上传对象实际大小超过限制或为空");
            }

            objectStorage.copy((String) claim.get("staging_key"), finalObjectKey);
            long finalSize = objectStorage.size(finalObjectKey);
            ImageInspector.Info imageInfo;
            try (InputStream input = objectStorage.open(finalObjectKey)) {
                imageInfo = imageInspector.inspect(input, finalSize);
            }

            return storyTransactions.inTransaction(
                    () -> {
                        Map<String, Object> story =
                                storyTransactions.ownedStory(
                                        number(claim, "story_id"), userId, true);
                        storyTransactions.checkLive(story);
                        Map<String, Object> attachment = storyTransactions.attachment(id, true);
                        if ("READY".equals(attachment.get("state"))) {
                            return publicInfo(attachment);
                        }

                        if (!"PENDING".equals(attachment.get("state"))
                                || !confirmationAttempt.equals(attachment.get("current_attempt"))
                                || !time(attachment, "confirm_lease_until")
                                        .isAfter(LocalDateTime.now())) {
                            throw StoryProblem.conflict("确认租约已失效，请重试");
                        }

                        int updatedRows =
                                storyTransactions.jdbcTemplate.update(
                                        "UPDATE tb_story_attachment SET state='READY',final_key=?,actual_format=?,actual_size=?,width=?,height=?,confirm_lease_until=NULL WHERE id=? AND state='PENDING' AND current_attempt=?",
                                        finalObjectKey,
                                        imageInfo.getFormat(),
                                        imageInfo.getSize(),
                                        imageInfo.getWidth(),
                                        imageInfo.getHeight(),
                                        id,
                                        confirmationAttempt);
                        if (updatedRows != 1) {
                            throw StoryProblem.conflict("附件确认状态冲突");
                        }
                        // Adopted final objects are excluded by DB predicate from all cleanup
                        // claims.
                        return publicInfo(storyTransactions.attachment(id, false));
                    });
        } catch (Exception exception) {
            // A failed DB commit cannot roll back the copy. The persisted attempt remains
            // sweepable.
            storyTransactions.inTransaction(
                    () -> {
                        storyTransactions.jdbcTemplate.update(
                                "UPDATE tb_story_attachment SET confirm_lease_until=NULL WHERE id=? AND state='PENDING' AND current_attempt=?",
                                id,
                                confirmationAttempt);
                        return null;
                    });
            if (exception instanceof StoryProblem) {
                throw (StoryProblem) exception;
            }
            // SDK messages can contain URLs. Do not return or log those messages.
            throw new StoryProblem(503, "对象不存在、存储不可用或确认提交失败，请检查上传后重试");
        }
    }

    public void remove(long id, long userId) {
        storyTransactions.inTransaction(
                () -> {
                    Map<String, Object> existingAttachment =
                            storyTransactions.attachment(id, false);
                    if (number(existingAttachment, "user_id") != userId) {
                        throw StoryProblem.missing();
                    }

                    Map<String, Object> story =
                            storyTransactions.ownedStory(
                                    number(existingAttachment, "story_id"), userId, true);
                    if ("PUBLISHED".equals(story.get("status"))) {
                        throw StoryProblem.conflict("已发布笔记请通过版本化编辑接口移除图片");
                    }

                    Map<String, Object> attachment = storyTransactions.attachment(id, true);
                    if ("DELETING".equals(attachment.get("state"))
                            || "DELETED".equals(attachment.get("state"))) {
                        return null;
                    }

                    storyTransactions.jdbcTemplate.update(
                            "UPDATE tb_story_attachment_ref SET active=0 WHERE attachment_id=?",
                            id);
                    storyTransactions.retireAttachment(attachment);
                    storyTransactions.jdbcTemplate.update(
                            "UPDATE tb_story SET version=version+1 WHERE id=?",
                            number(attachment, "story_id"));
                    return null;
                });
    }

    public List<Map<String, Object>> list(long storyId, long userId) {
        storyTransactions.checkLive(storyTransactions.ownedStory(storyId, userId, false));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> attachment :
                storyTransactions.jdbcTemplate.queryForList(
                        "SELECT * FROM tb_story_attachment WHERE story_id=? ORDER BY id",
                        storyId)) {
            result.add(publicInfo(attachment));
        }

        return result;
    }

    public String readUrl(long storyId, long id, Long userId) {
        Map<String, Object> story = storyTransactions.story(storyId, false);
        assertReadable(story, userId);
        Map<String, Object> attachment = storyTransactions.attachment(id, false);
        long activeReferenceCount =
                storyTransactions.jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM tb_story_attachment_ref WHERE story_id=? AND attachment_id=? AND active=1",
                        Long.class,
                        storyId,
                        id);
        if (activeReferenceCount != 1
                || number(attachment, "story_id") != storyId
                || !"READY".equals(attachment.get("state"))) {
            throw StoryProblem.missing();
        }

        try {
            return objectStorage.signGet(
                    (String) attachment.get("final_key"), storyFileProperties.getReadSeconds());
        } catch (Exception exception) {
            throw new StoryProblem(503, "图片访问链接暂不可用");
        }
    }

    public static void assertReadable(Map<String, Object> story, Long userId) {
        String state = (String) story.get("status");
        if ("DELETED".equals(state)
                || (!"PUBLISHED".equals(state)
                        && (userId == null || number(story, "user_id") != userId))) {
            throw StoryProblem.missing();
        }

        if ("DRAFT".equals(state)
                && !time(story, "draft_expires_at").isAfter(LocalDateTime.now())) {
            throw StoryProblem.missing();
        }
    }

    public static Map<String, Object> publicInfo(Map<String, Object> attachment) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", attachment.get("id"));
        result.put("state", attachment.get("state"));
        result.put("format", attachment.get("actual_format"));
        result.put("size", attachment.get("actual_size"));
        result.put("width", attachment.get("width"));
        result.put("height", attachment.get("height"));
        return result;
    }
}
