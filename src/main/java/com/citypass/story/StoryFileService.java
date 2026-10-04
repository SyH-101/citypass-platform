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
    private final StoryTransactions t;
    private final StoryFileProperties p;
    private final ObjectStorage storage;
    private final ImageInspector inspector;
    private final Semaphore io;

    public StoryFileService(StoryTransactions t,StoryFileProperties p,ObjectStorage storage,ImageInspector inspector) {
        this.t=t; this.p=p; this.storage=storage; this.inspector=inspector;
        this.io=new Semaphore(Math.max(1,p.getIoConcurrency()));
    }

    public Map<String,Object> requestUpload(long storyId,long user) {
        Map<String,Object> a=t.inTransaction(() -> {
            t.db.queryForObject("SELECT id FROM tb_user WHERE id=? FOR UPDATE",Long.class,user);
            Map<String,Object> story=t.ownedStory(storyId,user,true); t.checkLive(story);
            long quota=t.db.queryForObject("SELECT COUNT(*) FROM tb_story_attachment a WHERE user_id=? AND state='PENDING'",Long.class,user);
            if(quota>=p.getPendingQuota()) throw new StoryProblem(429,"待上传额度已满");
            long count=t.db.queryForObject("SELECT COUNT(*) FROM tb_story_attachment WHERE story_id=? AND state IN ('PENDING','READY')",Long.class,storyId);
            if(count>=p.getMaxImages()) throw new StoryProblem(422,"每篇图片额度已满，请移除不需要的附件");
            String staging="stories/staging/"+user+"/"+UUID.randomUUID();
            LocalDateTime deadline=LocalDateTime.now().plusSeconds(p.getUploadSeconds());
            long id=t.insert("INSERT INTO tb_story_attachment(user_id,story_id,staging_key,expires_at,upload_url_expires_at) VALUES(?,?,?,?,?)",
                    user,storyId,staging,deadline,deadline);
            t.db.update("INSERT INTO tb_story_file_object(attachment_id,object_key,kind,cleanup_after) VALUES(?,?,'STAGING',?)",id,staging,deadline.plusSeconds(p.getCleanupGraceSeconds()));
            return t.attachment(id,false);
        });
        try {
            Map<String,Object> result=publicInfo(a);
            int remaining=(int)Duration.between(LocalDateTime.now(),time(a,"upload_url_expires_at")).getSeconds();
            if(remaining<1) throw StoryProblem.conflict("上传凭证申请已超时，请移除附件后重新申请");
            // Signing must not extend the durable latest-expiry boundary used by cleanup.
            result.put("uploadUrl",storage.signPut((String)a.get("staging_key"),remaining));
            result.put("method","PUT"); result.put("expiresAt",a.get("upload_url_expires_at"));
            result.put("maxBytes",p.getMaxBytes());
            return result;
        } catch(Exception e) { throw new StoryProblem(503,"上传凭证生成失败，可移除此附件后重新申请"); }
    }

    public Map<String,Object> confirm(long id,long user) {
        if(!io.tryAcquire()) throw new StoryProblem(503,"图片处理繁忙，请稍后重试");
        try { return confirmWithPermit(id,user); } finally { io.release(); }
    }

    private Map<String,Object> confirmWithPermit(long id,long user) {
        Map<String,Object> claim=t.inTransaction(() -> {
            Map<String,Object> initial=t.attachment(id,false);
            if(number(initial,"user_id")!=user) throw StoryProblem.missing();
            Map<String,Object> story=t.ownedStory(number(initial,"story_id"),user,true); t.checkLive(story);
            Map<String,Object> a=t.attachment(id,true);
            if("READY".equals(a.get("state"))) return a;
            if(!"PENDING".equals(a.get("state"))) throw StoryProblem.conflict("附件已进入清理，不能确认");
            if(!time(a,"expires_at").isAfter(LocalDateTime.now())) throw StoryProblem.conflict("上传会话已过期");
            if(a.get("confirm_lease_until")!=null && time(a,"confirm_lease_until").isAfter(LocalDateTime.now())) throw StoryProblem.conflict("附件正在确认，请稍后重试");
            String attempt=UUID.randomUUID().toString();
            String key="stories/final/"+user+"/"+attempt;
            LocalDateTime lease=LocalDateTime.now().plusSeconds(p.getConfirmationLeaseSeconds());
            // Attempt and destination are persisted before external I/O, including crash windows.
            t.db.update("INSERT INTO tb_story_file_object(attachment_id,object_key,attempt_id,kind,cleanup_after) VALUES(?,?,?,'FINAL',?)",id,key,attempt,lease.plusSeconds(p.getCleanupGraceSeconds()));
            t.db.update("UPDATE tb_story_attachment SET current_attempt=?,confirm_lease_until=? WHERE id=? AND state='PENDING'",attempt,lease,id);
            a.put("attempt",attempt); a.put("candidate_key",key);
            return a;
        });
        if("READY".equals(claim.get("state"))) return publicInfo(claim);
        String attempt=(String)claim.get("attempt"), target=(String)claim.get("candidate_key");
        try {
            long stagingSize=storage.size((String)claim.get("staging_key"));
            if(stagingSize<=0 || stagingSize>p.getMaxBytes()) throw new StoryProblem(422,"上传对象实际大小超过限制或为空");
            storage.copy((String)claim.get("staging_key"),target);
            long finalSize=storage.size(target);
            ImageInspector.Info info;
            try(InputStream input=storage.open(target)) { info=inspector.inspect(input,finalSize); }
            return t.inTransaction(() -> {
                Map<String,Object> story=t.ownedStory(number(claim,"story_id"),user,true); t.checkLive(story);
                Map<String,Object> a=t.attachment(id,true);
                if("READY".equals(a.get("state"))) return publicInfo(a);
                if(!"PENDING".equals(a.get("state")) || !attempt.equals(a.get("current_attempt"))
                        || !time(a,"confirm_lease_until").isAfter(LocalDateTime.now())) throw StoryProblem.conflict("确认租约已失效，请重试");
                int changed=t.db.update("UPDATE tb_story_attachment SET state='READY',final_key=?,actual_format=?,actual_size=?,width=?,height=?,confirm_lease_until=NULL WHERE id=? AND state='PENDING' AND current_attempt=?",
                        target,info.getFormat(),info.getSize(),info.getWidth(),info.getHeight(),id,attempt);
                if(changed!=1) throw StoryProblem.conflict("附件确认状态冲突");
                // Adopted final objects are excluded by DB predicate from all cleanup claims.
                return publicInfo(t.attachment(id,false));
            });
        } catch(Exception e) {
            // A failed DB commit cannot roll back the copy. The persisted attempt remains sweepable.
            t.inTransaction(() -> {
                t.db.update("UPDATE tb_story_attachment SET confirm_lease_until=NULL WHERE id=? AND state='PENDING' AND current_attempt=?",id,attempt);
                return null;
            });
            if(e instanceof StoryProblem) throw (StoryProblem)e;
            // SDK messages can contain URLs. Do not return or log those messages.
            throw new StoryProblem(503,"对象不存在、存储不可用或确认提交失败，请检查上传后重试");
        }
    }

    public void remove(long id,long user) {
        t.inTransaction(() -> {
            Map<String,Object> initial=t.attachment(id,false);
            if(number(initial,"user_id")!=user) throw StoryProblem.missing();
            Map<String,Object> story=t.ownedStory(number(initial,"story_id"),user,true);
            if("PUBLISHED".equals(story.get("status"))) throw StoryProblem.conflict("已发布笔记请通过版本化编辑接口移除图片");
            Map<String,Object> a=t.attachment(id,true);
            if("DELETING".equals(a.get("state")) || "DELETED".equals(a.get("state"))) return null;
            t.db.update("UPDATE tb_story_attachment_ref SET active=0 WHERE attachment_id=?",id);
            t.retireAttachment(a);
            t.db.update("UPDATE tb_story SET version=version+1 WHERE id=?",number(a,"story_id"));
            return null;
        });
    }

    public List<Map<String,Object>> list(long storyId,long user) {
        t.checkLive(t.ownedStory(storyId,user,false));
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> a:t.db.queryForList("SELECT * FROM tb_story_attachment WHERE story_id=? ORDER BY id",storyId)) result.add(publicInfo(a));
        return result;
    }
    public String readUrl(long storyId,long id,Long user) {
        Map<String,Object> story=t.story(storyId,false);
        assertReadable(story,user);
        Map<String,Object> a=t.attachment(id,false);
        long count=t.db.queryForObject("SELECT COUNT(*) FROM tb_story_attachment_ref WHERE story_id=? AND attachment_id=? AND active=1",Long.class,storyId,id);
        if(count!=1 || number(a,"story_id")!=storyId || !"READY".equals(a.get("state"))) throw StoryProblem.missing();
        try { return storage.signGet((String)a.get("final_key"),p.getReadSeconds()); }
        catch(Exception e) { throw new StoryProblem(503,"图片访问链接暂不可用"); }
    }
    public static void assertReadable(Map<String,Object> story,Long user) {
        String state=(String)story.get("status");
        if("DELETED".equals(state) || (!"PUBLISHED".equals(state) && (user==null || number(story,"user_id")!=user))) throw StoryProblem.missing();
        if("DRAFT".equals(state) && !time(story,"draft_expires_at").isAfter(LocalDateTime.now())) throw StoryProblem.missing();
    }
    public static Map<String,Object> publicInfo(Map<String,Object> a) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",a.get("id")); result.put("state",a.get("state"));
        result.put("format",a.get("actual_format")); result.put("size",a.get("actual_size"));
        result.put("width",a.get("width")); result.put("height",a.get("height"));
        return result;
    }
}
