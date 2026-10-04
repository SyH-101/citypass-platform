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
    private final StoryTransactions t;
    private final StoryDraftService drafts;
    private final StoryFileProperties p;
    private final ObjectStorage storage;
    public StoryFileCleanup(StoryTransactions t,StoryDraftService drafts,StoryFileProperties p,ObjectStorage storage) {
        this.t=t; this.drafts=drafts; this.p=p; this.storage=storage;
    }

    @Scheduled(fixedDelayString="${story-files.sweep-delay-ms:5000}")
    public void sweep() {
        try {
            for(Long id:t.db.queryForList("SELECT id FROM tb_story WHERE status='DRAFT' AND draft_expires_at<=NOW() ORDER BY id LIMIT ?",Long.class,p.getSweepBatch())) {
                t.inTransaction(() -> {
                    Map<String,Object> story=t.story(id,true);
                    if("DRAFT".equals(story.get("status")) && !time(story,"draft_expires_at").isAfter(LocalDateTime.now())) drafts.deleteLocked(story);
                    return null;
                });
            }
            for(Long id:t.db.queryForList("SELECT a.id FROM tb_story_attachment a WHERE a.state IN ('PENDING','READY') AND a.expires_at<=NOW() AND NOT EXISTS(SELECT 1 FROM tb_story_attachment_ref r WHERE r.attachment_id=a.id AND r.active=1) ORDER BY a.id LIMIT ?",Long.class,p.getSweepBatch())) {
                t.inTransaction(() -> {
                    Map<String,Object> initial=t.attachment(id,false);
                    t.story(number(initial,"story_id"),true);
                    Map<String,Object> a=t.attachment(id,true);
                    long refs=t.db.queryForObject("SELECT COUNT(*) FROM tb_story_attachment_ref WHERE attachment_id=? AND active=1",Long.class,id);
                    if(refs==0 && ("PENDING".equals(a.get("state")) || "READY".equals(a.get("state")))
                            && !time(a,"expires_at").isAfter(LocalDateTime.now())) t.retireAttachment(a);
                    return null;
                });
            }
            // Sweep tombstones too. A PUT begun before expiry can finish after the first deletion.
            for(Long id:t.db.queryForList("SELECT o.id FROM tb_story_file_object o JOIN tb_story_attachment a ON a.id=o.attachment_id LEFT JOIN tb_reliable_task task ON task.id=o.cleanup_task_id WHERE o.cleanup_after<=NOW() AND (o.last_scheduled_at IS NULL OR o.last_scheduled_at<=DATE_SUB(NOW(),INTERVAL ? SECOND)) AND (task.id IS NULL OR task.status='DONE') AND NOT(a.state='READY' AND a.final_key=o.object_key) ORDER BY o.last_scheduled_at,o.id LIMIT ?",
                    Long.class,p.getTombstoneRescanSeconds(),p.getSweepBatch())) {
                t.inTransaction(() -> {
                    List<Map<String,Object>> rows=t.db.queryForList("SELECT * FROM tb_story_file_object WHERE id=?",id);
                    if(rows.isEmpty()) return null;
                    Map<String,Object> o=rows.get(0);
                    Map<String,Object> initial=t.attachment(number(o,"attachment_id"),false);
                    t.story(number(initial,"story_id"),true);
                    Map<String,Object> a=t.attachment(number(o,"attachment_id"),true);
                    if(!canDelete(o,a)) return null;
                    List<Map<String,Object>> locked=t.db.queryForList("SELECT * FROM tb_story_file_object WHERE id=? FOR UPDATE",id);
                    o=locked.get(0);
                    // Keep the existing retry/dead-letter task as the sole owner of this object.
                    // Without this fence, an outage creates a new task on every tombstone sweep.
                    if(o.get("cleanup_task_id")!=null) {
                        List<String> statuses=t.db.queryForList("SELECT status FROM tb_reliable_task WHERE id=?",String.class,o.get("cleanup_task_id"));
                        if(!statuses.isEmpty() && !"DONE".equals(statuses.get(0))) return null;
                    }
                    if(o.get("last_scheduled_at")!=null && time(o,"last_scheduled_at").plusSeconds(p.getTombstoneRescanSeconds()).isAfter(LocalDateTime.now())) return null;
                    String round=UUID.randomUUID().toString();
                    String key="delete-story-file:"+id+":"+round;
                    t.tasks.enqueue(ReliableTaskRepository.DELETE_STORY_FILE,key,String.valueOf(id));
                    Long taskId=t.db.queryForObject("SELECT id FROM tb_reliable_task WHERE biz_key=?",Long.class,key);
                    t.db.update("UPDATE tb_story_file_object SET last_scheduled_at=NOW(),cleanup_task_id=? WHERE id=?",taskId,id);
                    return null;
                });
            }
        } catch(Exception e) { log.warn("笔记文件清理扫描失败，将在下一轮重试",e); }
    }

    boolean canDelete(Map<String,Object> o,Map<String,Object> a) {
        if(time(o,"cleanup_after").isAfter(LocalDateTime.now())) return false;
        if("READY".equals(a.get("state")) && o.get("object_key").equals(a.get("final_key"))) return false;
        if("FINAL".equals(o.get("kind")) && "PENDING".equals(a.get("state"))
                && Objects.equals(o.get("attempt_id"),a.get("current_attempt")) && a.get("confirm_lease_until")!=null
                && time(a,"confirm_lease_until").isAfter(LocalDateTime.now())) return false;
        return true;
    }

    public void execute(String payload) {
        long id=Long.parseLong(payload);
        String key=t.inTransaction(() -> {
            List<Map<String,Object>> rows=t.db.queryForList("SELECT * FROM tb_story_file_object WHERE id=?",id);
            if(rows.isEmpty()) return null;
            Map<String,Object> o=rows.get(0);
            Map<String,Object> initial=t.attachment(number(o,"attachment_id"),false);
            t.story(number(initial,"story_id"),true);
            Map<String,Object> a=t.attachment(number(o,"attachment_id"),true);
            if(!canDelete(o,a)) return null;
            // The candidate FINAL attempt cannot be adopted after its lease ends.
            // STAGING can never become a published object. DELETING cannot be rebound.
            return (String)o.get("object_key");
        });
        if(key==null) return;
        try { storage.delete(key); } catch(Exception e) { throw new IllegalStateException("对象存储删除失败，请通过可靠任务重试"); }
        t.inTransaction(() -> {
            Map<String,Object> o=t.db.queryForMap("SELECT * FROM tb_story_file_object WHERE id=?",id);
            long attachmentId=number(o,"attachment_id");
            Map<String,Object> initial=t.attachment(attachmentId,false);
            t.story(number(initial,"story_id"),true);
            Map<String,Object> a=t.attachment(attachmentId,true);
            t.db.update("UPDATE tb_story_file_object SET state='DELETED',last_deleted_at=NOW() WHERE id=?",id);
            if("DELETING".equals(a.get("state"))) {
                long remaining=t.db.queryForObject("SELECT COUNT(*) FROM tb_story_file_object WHERE attachment_id=? AND state<>'DELETED'",Long.class,attachmentId);
                if(remaining==0) t.db.update("UPDATE tb_story_attachment SET state='DELETED' WHERE id=? AND state='DELETING'",attachmentId);
            }
            return null;
        });
    }
}
