package com.citypass.story;

import com.citypass.config.StoryFileProperties;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;
import static com.citypass.story.StoryTransactions.*;

@Service
public class StoryDraftService {

    private final StoryTransactions storyTransactions;
    private final StoryFileProperties storyFileProperties;

    public StoryDraftService(
            StoryTransactions storyTransactions, StoryFileProperties storyFileProperties) {
        this.storyTransactions = storyTransactions;
        this.storyFileProperties = storyFileProperties;
    }

    public Map<String, Object> create(
            long userId, String idempotencyKey, StoryEditRequest request) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new StoryProblem(400, "创建草稿需提供 8-64 位 Idempotency-Key");
        }

        validateContent(request, false);
        return storyTransactions.inTransaction(
                () -> {
                    storyTransactions.jdbcTemplate.queryForObject(
                            "SELECT id FROM tb_user WHERE id=? FOR UPDATE", Long.class, userId);
                    List<Map<String, Object>> existingDrafts =
                            storyTransactions.jdbcTemplate.queryForList(
                                    "SELECT id,version,status FROM tb_story WHERE user_id=? AND client_key=?",
                                    userId,
                                    idempotencyKey);
                    if (!existingDrafts.isEmpty()) {
                        return existingDrafts.get(0);
                    }

                    Long draftCount =
                            storyTransactions.jdbcTemplate.queryForObject(
                                    "SELECT COUNT(*) FROM tb_story WHERE user_id=? AND status='DRAFT'",
                                    Long.class,
                                    userId);
                    if (draftCount >= storyFileProperties.getPendingQuota()) {
                        throw new StoryProblem(429, "草稿额度已满，请删除或发布已有草稿");
                    }

                    validateActivity(request.getActivityPassId());
                    long id =
                            storyTransactions.insert(
                                    "INSERT INTO tb_story(venue_id,user_id,title,content,images,status,version,activity_pass_id,draft_expires_at,client_key) VALUES(0,?,?,?,'','DRAFT',1,?,?,?)",
                                    userId,
                                    text(request.getTitle()),
                                    text(request.getContent()),
                                    request.getActivityPassId(),
                                    LocalDateTime.now()
                                            .plusHours(storyFileProperties.getDraftHours()),
                                    idempotencyKey);
                    return result(id, 1, "DRAFT");
                });
    }

    public Map<String, Object> edit(long id, long userId, StoryEditRequest request) {
        validateContent(request, false);
        if (request.getVersion() == null) {
            throw new StoryProblem(400, "必须提供当前版本号");
        }

        return storyTransactions.inTransaction(
                () -> {
                    Map<String, Object> story = storyTransactions.ownedStory(id, userId, true);
                    storyTransactions.checkLive(story);
                    if (number(story, "version") != request.getVersion()) {
                        throw StoryProblem.conflict("笔记已被修改，请刷新后重试");
                    }

                    validateActivity(request.getActivityPassId());
                    boolean published = "PUBLISHED".equals(story.get("status"));
                    if (published) {
                        validateContent(request, true);
                    }

                    bind(story, userId, request);
                    int updatedRows =
                            storyTransactions.jdbcTemplate.update(
                                    "UPDATE tb_story SET title=?,content=?,activity_pass_id=?,version=version+1,update_time=NOW() WHERE id=? AND version=? AND status<>'DELETED'",
                                    text(request.getTitle()),
                                    text(request.getContent()),
                                    request.getActivityPassId(),
                                    id,
                                    request.getVersion());
                    if (updatedRows != 1) {
                        throw StoryProblem.conflict("笔记版本冲突");
                    }

                    long version = request.getVersion() + 1;
                    if (published) {
                        storyTransactions.queueFeed(id, version);
                    }

                    return result(id, version, (String) story.get("status"));
                });
    }

    public Map<String, Object> publish(long id, long userId, long expectedVersion) {
        return storyTransactions.inTransaction(
                () -> {
                    Map<String, Object> story = storyTransactions.ownedStory(id, userId, true);
                    storyTransactions.checkLive(story);
                    // A retry acknowledges the existing publication. Editing uses the edit
                    // endpoint.
                    if ("PUBLISHED".equals(story.get("status"))) {
                        return result(id, number(story, "version"), "PUBLISHED");
                    }

                    if (number(story, "version") != expectedVersion) {
                        throw StoryProblem.conflict("笔记版本冲突");
                    }

                    StoryEditRequest contentRequest = new StoryEditRequest();
                    contentRequest.setTitle((String) story.get("title"));
                    contentRequest.setContent((String) story.get("content"));
                    validateContent(contentRequest, true);
                    Object activityPassIdValue = story.get("activity_pass_id");
                    if (activityPassIdValue != null) {
                        validateActivity(((Number) activityPassIdValue).longValue());
                    }

                    List<Map<String, Object>> attachments =
                            storyTransactions.jdbcTemplate.queryForList(
                                    "SELECT a.* FROM tb_story_attachment_ref r JOIN tb_story_attachment a ON a.id=r.attachment_id WHERE r.story_id=? AND r.active=1 ORDER BY a.id FOR UPDATE",
                                    id);
                    if (attachments.size() > storyFileProperties.getMaxImages()) {
                        throw new StoryProblem(422, "图片数量超过限制");
                    }

                    for (Map<String, Object> attachment : attachments) {
                        if (number(attachment, "user_id") != userId
                                || !"READY".equals(attachment.get("state"))
                                || attachment.get("final_key") == null) {
                            throw StoryProblem.conflict("笔记存在尚未就绪的附件");
                        }
                    }

                    long version = expectedVersion + 1;
                    if (storyTransactions.jdbcTemplate.update(
                                    "UPDATE tb_story SET status='PUBLISHED',version=?,publish_time=NOW(3),draft_expires_at=NULL WHERE id=? AND status='DRAFT' AND version=?",
                                    version,
                                    id,
                                    expectedVersion)
                            != 1) {
                        throw StoryProblem.conflict("发布状态冲突");
                    }

                    storyTransactions.queueFeed(id, version);
                    return result(id, version, "PUBLISHED");
                });
    }

    public Map<String, Object> delete(long id, long userId, long expectedVersion) {
        return storyTransactions.inTransaction(
                () -> {
                    Map<String, Object> story = storyTransactions.ownedStory(id, userId, true);
                    if ("DELETED".equals(story.get("status"))) {
                        return result(id, number(story, "version"), "DELETED");
                    }

                    if (number(story, "version") != expectedVersion) {
                        throw StoryProblem.conflict("笔记版本冲突");
                    }

                    deleteLocked(story);
                    return result(id, expectedVersion + 1, "DELETED");
                });
    }

    void deleteLocked(Map<String, Object> story) {
        long id = number(story, "id");
        storyTransactions.jdbcTemplate.update(
                "UPDATE tb_story SET status='DELETED',version=version+1 WHERE id=? AND status<>'DELETED'",
                id);
        storyTransactions.jdbcTemplate.update(
                "UPDATE tb_story_attachment_ref SET active=0 WHERE story_id=?", id);
        for (Map<String, Object> attachment :
                storyTransactions.jdbcTemplate.queryForList(
                        "SELECT * FROM tb_story_attachment WHERE story_id=? ORDER BY id FOR UPDATE",
                        id)) {
            storyTransactions.retireAttachment(attachment);
        }

        storyTransactions.queueFeed(id, number(story, "version") + 1);
    }

    private void bind(Map<String, Object> story, long userId, StoryEditRequest request) {
        long id = number(story, "id");
        List<Long> attachmentIds = request.getAttachmentIds();
        if (attachmentIds == null) {
            attachmentIds =
                    storyTransactions.jdbcTemplate.queryForList(
                            "SELECT attachment_id FROM tb_story_attachment_ref WHERE story_id=? AND active=1 ORDER BY position",
                            Long.class,
                            id);
        }

        if (attachmentIds.size() > storyFileProperties.getMaxImages()
                || attachmentIds.contains(null)
                || new HashSet<>(attachmentIds).size() != attachmentIds.size()) {
            throw new StoryProblem(422, "图片数量超过限制或含重复编号");
        }

        List<Long> sortedAttachmentIds = new ArrayList<>(attachmentIds);
        Collections.sort(sortedAttachmentIds);
        for (Long attachmentId : sortedAttachmentIds) {
            Map<String, Object> attachment = storyTransactions.attachment(attachmentId, true);
            if (number(attachment, "user_id") != userId || number(attachment, "story_id") != id) {
                throw StoryProblem.missing();
            }

            if (!"READY".equals(attachment.get("state"))) {
                throw StoryProblem.conflict("只能关联已确认且未进入清理的图片");
            }
        }

        List<Long> previousAttachmentIds =
                storyTransactions.jdbcTemplate.queryForList(
                        "SELECT attachment_id FROM tb_story_attachment_ref WHERE story_id=? AND active=1 ORDER BY attachment_id",
                        Long.class,
                        id);
        storyTransactions.jdbcTemplate.update(
                "UPDATE tb_story_attachment_ref SET active=0 WHERE story_id=?", id);
        for (int i = 0; i < attachmentIds.size(); i++) {
            storyTransactions.jdbcTemplate.update(
                    "INSERT INTO tb_story_attachment_ref(story_id,attachment_id,position,active) VALUES(?,?,?,1) ON DUPLICATE KEY UPDATE position=VALUES(position),active=1",
                    id,
                    attachmentIds.get(i),
                    i);
        }

        for (Long removedAttachmentId : previousAttachmentIds) {
            if (!attachmentIds.contains(removedAttachmentId)) {
                storyTransactions.retireAttachment(
                        storyTransactions.attachment(removedAttachmentId, true));
            }
        }

        if (request.getLegacyImages() != null) {
            List<String> existingLegacyImages = legacy((String) story.get("images"));
            if (!existingLegacyImages.containsAll(request.getLegacyImages())) {
                throw new StoryProblem(422, "不能添加未经归属验证的历史图片路径");
            }

            if (request.getLegacyImages().size() + attachmentIds.size()
                    > storyFileProperties.getMaxImages()) {
                throw new StoryProblem(422, "图片数量超过限制");
            }

            storyTransactions.jdbcTemplate.update(
                    "UPDATE tb_story SET images=? WHERE id=?",
                    String.join(",", request.getLegacyImages()),
                    id);
        } else if (legacy((String) story.get("images")).size() + attachmentIds.size()
                > storyFileProperties.getMaxImages()) {
            throw new StoryProblem(422, "图片数量超过限制");
        }
    }

    private void validateContent(StoryEditRequest request, boolean publish) {
        if (request == null
                || text(request.getTitle()).length() > 255
                || text(request.getContent()).length() > 2048) {
            throw new StoryProblem(422, "标题最多 255 字，正文最多 2048 字");
        }

        if (publish
                && (text(request.getTitle()).isEmpty() || text(request.getContent()).isEmpty())) {
            throw new StoryProblem(422, "发布前需填写标题和正文");
        }
    }

    private void validateActivity(Long activityPassId) {
        if (activityPassId != null
                && storyTransactions.jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM tb_activity_pass WHERE id=? AND status=1",
                                Long.class,
                                activityPassId)
                        != 1) {
            throw new StoryProblem(422, "关联活动不存在或已下架");
        }
    }

    public static List<String> legacy(String images) {
        if (images == null || images.trim().isEmpty()) {
            return Collections.emptyList();
        }

        return Arrays.asList(images.split(","));
    }

    private static String text(String text) {
        return text == null ? "" : text.trim();
    }

    private static Map<String, Object> result(long id, long version, String status) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("version", version);
        result.put("status", status);
        return result;
    }
}
