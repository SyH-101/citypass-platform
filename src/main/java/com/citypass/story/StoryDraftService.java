package com.citypass.story;

import com.citypass.config.StoryFileProperties;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import static com.citypass.story.StoryTransactions.*;

@Service
public class StoryDraftService {
    private final StoryTransactions t;
    private final StoryFileProperties p;
    public StoryDraftService(StoryTransactions t, StoryFileProperties p) { this.t=t; this.p=p; }

    public Map<String,Object> create(long user, String key, StoryEditRequest request) {
        if (key==null || !key.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new StoryProblem(400,"创建草稿需提供 8-64 位 Idempotency-Key");
        }
        validateContent(request,false);
        return t.inTransaction(() -> {
            t.db.queryForObject("SELECT id FROM tb_user WHERE id=? FOR UPDATE",Long.class,user);
            List<Map<String,Object>> found=t.db.queryForList("SELECT id,version,status FROM tb_story WHERE user_id=? AND client_key=?",user,key);
            if (!found.isEmpty()) return found.get(0);
            Long drafts=t.db.queryForObject("SELECT COUNT(*) FROM tb_story WHERE user_id=? AND status='DRAFT'",Long.class,user);
            if (drafts>=p.getPendingQuota()) throw new StoryProblem(429,"草稿额度已满，请删除或发布已有草稿");
            validateActivity(request.getActivityPassId());
            long id=t.insert("INSERT INTO tb_story(venue_id,user_id,title,content,images,status,version,activity_pass_id,draft_expires_at,client_key) VALUES(0,?,?,?,'','DRAFT',1,?,?,?)",
                    user,text(request.getTitle()),text(request.getContent()),request.getActivityPassId(),LocalDateTime.now().plusHours(p.getDraftHours()),key);
            return result(id,1,"DRAFT");
        });
    }

    public Map<String,Object> edit(long id,long user,StoryEditRequest request) {
        validateContent(request,false);
        if(request.getVersion()==null) throw new StoryProblem(400,"必须提供当前版本号");
        return t.inTransaction(() -> {
            Map<String,Object> story=t.ownedStory(id,user,true); t.checkLive(story);
            if(number(story,"version")!=request.getVersion()) throw StoryProblem.conflict("笔记已被修改，请刷新后重试");
            validateActivity(request.getActivityPassId());
            boolean published="PUBLISHED".equals(story.get("status"));
            if(published) validateContent(request,true);
            bind(story,user,request);
            int changed=t.db.update("UPDATE tb_story SET title=?,content=?,activity_pass_id=?,version=version+1,update_time=NOW() WHERE id=? AND version=? AND status<>'DELETED'",
                    text(request.getTitle()),text(request.getContent()),request.getActivityPassId(),id,request.getVersion());
            if(changed!=1) throw StoryProblem.conflict("笔记版本冲突");
            long version=request.getVersion()+1;
            if(published) t.queueFeed(id,version);
            return result(id,version,(String)story.get("status"));
        });
    }

    public Map<String,Object> publish(long id,long user,long expectedVersion) {
        return t.inTransaction(() -> {
            Map<String,Object> story=t.ownedStory(id,user,true); t.checkLive(story);
            // A retry acknowledges the existing publication. Editing uses the edit endpoint.
            if("PUBLISHED".equals(story.get("status"))) return result(id,number(story,"version"),"PUBLISHED");
            if(number(story,"version")!=expectedVersion) throw StoryProblem.conflict("笔记版本冲突");
            StoryEditRequest content=new StoryEditRequest(); content.setTitle((String)story.get("title")); content.setContent((String)story.get("content"));
            validateContent(content,true);
            Object activity=story.get("activity_pass_id");
            if(activity!=null) validateActivity(((Number)activity).longValue());
            List<Map<String,Object>> refs=t.db.queryForList("SELECT a.* FROM tb_story_attachment_ref r JOIN tb_story_attachment a ON a.id=r.attachment_id WHERE r.story_id=? AND r.active=1 ORDER BY a.id FOR UPDATE",id);
            if(refs.size()>p.getMaxImages()) throw new StoryProblem(422,"图片数量超过限制");
            for(Map<String,Object> ref:refs) {
                if(number(ref,"user_id")!=user || !"READY".equals(ref.get("state")) || ref.get("final_key")==null) {
                    throw StoryProblem.conflict("笔记存在尚未就绪的附件");
                }
            }
            long version=expectedVersion+1;
            if(t.db.update("UPDATE tb_story SET status='PUBLISHED',version=?,publish_time=NOW(3),draft_expires_at=NULL WHERE id=? AND status='DRAFT' AND version=?",version,id,expectedVersion)!=1) {
                throw StoryProblem.conflict("发布状态冲突");
            }
            t.queueFeed(id,version);
            return result(id,version,"PUBLISHED");
        });
    }

    public Map<String,Object> delete(long id,long user,long expectedVersion) {
        return t.inTransaction(() -> {
            Map<String,Object> story=t.ownedStory(id,user,true);
            if("DELETED".equals(story.get("status"))) return result(id,number(story,"version"),"DELETED");
            if(number(story,"version")!=expectedVersion) throw StoryProblem.conflict("笔记版本冲突");
            deleteLocked(story);
            return result(id,expectedVersion+1,"DELETED");
        });
    }

    void deleteLocked(Map<String,Object> story) {
        long id=number(story,"id");
        t.db.update("UPDATE tb_story SET status='DELETED',version=version+1 WHERE id=? AND status<>'DELETED'",id);
        t.db.update("UPDATE tb_story_attachment_ref SET active=0 WHERE story_id=?",id);
        for(Map<String,Object> a:t.db.queryForList("SELECT * FROM tb_story_attachment WHERE story_id=? ORDER BY id FOR UPDATE",id)) t.retireAttachment(a);
        t.queueFeed(id,number(story,"version")+1);
    }

    private void bind(Map<String,Object> story,long user,StoryEditRequest request) {
        long id=number(story,"id");
        List<Long> ids=request.getAttachmentIds();
        if(ids==null) ids=t.db.queryForList("SELECT attachment_id FROM tb_story_attachment_ref WHERE story_id=? AND active=1 ORDER BY position",Long.class,id);
        if(ids.size()>p.getMaxImages() || ids.contains(null) || new HashSet<>(ids).size()!=ids.size()) throw new StoryProblem(422,"图片数量超过限制或含重复编号");
        List<Long> sorted=new ArrayList<>(ids); Collections.sort(sorted);
        for(Long attachmentId:sorted) {
            Map<String,Object> a=t.attachment(attachmentId,true);
            if(number(a,"user_id")!=user || number(a,"story_id")!=id) throw StoryProblem.missing();
            if(!"READY".equals(a.get("state"))) throw StoryProblem.conflict("只能关联已确认且未进入清理的图片");
        }
        List<Long> old=t.db.queryForList("SELECT attachment_id FROM tb_story_attachment_ref WHERE story_id=? AND active=1 ORDER BY attachment_id",Long.class,id);
        t.db.update("UPDATE tb_story_attachment_ref SET active=0 WHERE story_id=?",id);
        for(int i=0;i<ids.size();i++) t.db.update("INSERT INTO tb_story_attachment_ref(story_id,attachment_id,position,active) VALUES(?,?,?,1) ON DUPLICATE KEY UPDATE position=VALUES(position),active=1",id,ids.get(i),i);
        for(Long removed:old) if(!ids.contains(removed)) t.retireAttachment(t.attachment(removed,true));
        if(request.getLegacyImages()!=null) {
            List<String> existing=legacy((String)story.get("images"));
            if(!existing.containsAll(request.getLegacyImages())) throw new StoryProblem(422,"不能添加未经归属验证的历史图片路径");
            if(request.getLegacyImages().size()+ids.size()>p.getMaxImages()) throw new StoryProblem(422,"图片数量超过限制");
            t.db.update("UPDATE tb_story SET images=? WHERE id=?",String.join(",",request.getLegacyImages()),id);
        } else if(legacy((String)story.get("images")).size()+ids.size()>p.getMaxImages()) throw new StoryProblem(422,"图片数量超过限制");
    }

    private void validateContent(StoryEditRequest r,boolean publish) {
        if(r==null || text(r.getTitle()).length()>255 || text(r.getContent()).length()>2048) throw new StoryProblem(422,"标题最多 255 字，正文最多 2048 字");
        if(publish && (text(r.getTitle()).isEmpty() || text(r.getContent()).isEmpty())) throw new StoryProblem(422,"发布前需填写标题和正文");
    }
    private void validateActivity(Long id) {
        if(id!=null && t.db.queryForObject("SELECT COUNT(*) FROM tb_activity_pass WHERE id=? AND status=1",Long.class,id)!=1) throw new StoryProblem(422,"关联活动不存在或已下架");
    }
    public static List<String> legacy(String images) {
        if(images==null || images.trim().isEmpty()) return Collections.emptyList();
        return Arrays.asList(images.split(","));
    }
    private static String text(String text) { return text==null?"":text.trim(); }
    private static Map<String,Object> result(long id,long version,String status) {
        Map<String,Object> map=new LinkedHashMap<>(); map.put("id",id); map.put("version",version); map.put("status",status); return map;
    }
}
