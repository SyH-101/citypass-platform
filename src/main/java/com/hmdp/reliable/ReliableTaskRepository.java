package com.hmdp.reliable;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public class ReliableTaskRepository {

    public static final String ORDER_TIMEOUT = "ORDER_TIMEOUT";
    public static final String RESTORE_REDIS_STOCK = "RESTORE_REDIS_STOCK";
    public static final String INIT_SECKILL_STOCK = "INIT_SECKILL_STOCK";

    private final JdbcTemplate jdbcTemplate;

    public ReliableTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void enqueue(String taskType, String bizKey, String payload) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO tb_reliable_task(task_type,biz_key,payload,status,retry_count,next_retry_time) " +
                        "VALUES (?,?,?,'PENDING',0,NOW())",
                taskType, bizKey, payload);
    }

    public List<ReliableTask> findReady(int limit) {
        return jdbcTemplate.query(
                "SELECT id,task_type,payload,retry_count FROM tb_reliable_task " +
                        "WHERE status='PENDING' AND next_retry_time<=NOW() ORDER BY id LIMIT ?",
                new Object[]{limit},
                (rs, rowNum) -> {
                    ReliableTask task = new ReliableTask();
                    task.setId(rs.getLong("id"));
                    task.setTaskType(rs.getString("task_type"));
                    task.setPayload(rs.getString("payload"));
                    task.setRetryCount(rs.getInt("retry_count"));
                    return task;
                });
    }

    public void markDone(Long id) {
        jdbcTemplate.update("UPDATE tb_reliable_task SET status='DONE',update_time=NOW() WHERE id=?", id);
    }

    public void markFailed(Long id, int retryCount, String error) {
        int delaySeconds = Math.min(300, 1 << Math.min(retryCount, 8));
        LocalDateTime next = LocalDateTime.now().plusSeconds(delaySeconds);
        String safeError = error == null ? "unknown" : error.substring(0, Math.min(error.length(), 500));
        jdbcTemplate.update(
                "UPDATE tb_reliable_task SET retry_count=retry_count+1,next_retry_time=?,last_error=?,update_time=NOW() WHERE id=?",
                next, safeError, id);
    }
}
