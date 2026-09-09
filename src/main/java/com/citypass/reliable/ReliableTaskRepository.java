package com.citypass.reliable;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Transactional outbox repository with task-level leases and fencing versions. */
@Repository
public class ReliableTaskRepository {

    public static final String CREATE_RESERVATION = "CREATE_RESERVATION";
    public static final String ORDER_TIMEOUT = "ORDER_TIMEOUT";
    public static final String RESTORE_REDIS_STOCK = "RESTORE_REDIS_STOCK";
    public static final String INIT_RESERVATION_STOCK = "INIT_RESERVATION_STOCK";
    public static final String TRANSFER_RESERVATION_CLAIM = "TRANSFER_RESERVATION_CLAIM";
    public static final String INVALIDATE_VENUE_CACHE = "INVALIDATE_VENUE_CACHE";

    public enum FailureDisposition { RETRY, DEAD, LOST_LEASE }

    private final JdbcTemplate jdbcTemplate;

    @Value("${reliable-task.max-retry:12}")
    private int defaultMaxRetry;

    public ReliableTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void enqueue(String taskType, String bizKey, String payload) {
        enqueueAt(taskType, bizKey, payload, LocalDateTime.now());
    }

    public void enqueueAt(String taskType, String bizKey, String payload, LocalDateTime executeAt) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO tb_reliable_task" +
                        "(task_type,biz_key,payload,status,retry_count,max_retry,next_retry_time,version) " +
                        "VALUES (?,?,?,'PENDING',0,?,?,0)",
                taskType, bizKey, payload, Math.max(1, defaultMaxRetry), executeAt);
    }

    /**
     * Claims independent tasks in a short transaction. SKIP LOCKED belongs here; the business FIFO queue
     * deliberately uses a blocking head lock instead.
     */
    @Transactional(rollbackFor = Exception.class)
    public List<ReliableTask> claimReady(int limit, String owner, int leaseSeconds) {
        List<ReliableTask> candidates = jdbcTemplate.query(
                "SELECT id,task_type,payload,retry_count,max_retry,version FROM tb_reliable_task " +
                        "WHERE (status='PENDING' AND next_retry_time<=NOW()) " +
                        "OR (status='RUNNING' AND lease_until<=NOW()) " +
                        "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
                new Object[]{limit},
                (rs, rowNum) -> {
                    ReliableTask task = new ReliableTask();
                    task.setId(rs.getLong("id"));
                    task.setTaskType(rs.getString("task_type"));
                    task.setPayload(rs.getString("payload"));
                    task.setRetryCount(rs.getInt("retry_count"));
                    task.setMaxRetry(rs.getInt("max_retry"));
                    task.setVersion(rs.getLong("version"));
                    return task;
                });

        LocalDateTime leaseUntil = LocalDateTime.now().plusSeconds(Math.max(1, leaseSeconds));
        List<ReliableTask> claimed = new ArrayList<>(candidates.size());
        for (ReliableTask task : candidates) {
            int changed = jdbcTemplate.update(
                    "UPDATE tb_reliable_task SET status='RUNNING',locked_by=?,lease_until=?," +
                            "version=version+1,update_time=NOW() WHERE id=? AND version=?",
                    owner, leaseUntil, task.getId(), task.getVersion());
            if (changed == 1) {
                task.setLockedBy(owner);
                task.setLeaseUntil(leaseUntil);
                task.setVersion(task.getVersion() + 1);
                claimed.add(task);
            }
        }
        return claimed;
    }

    public boolean markDone(ReliableTask task) {
        return jdbcTemplate.update(
                "UPDATE tb_reliable_task SET status='DONE',locked_by=NULL,lease_until=NULL,update_time=NOW() " +
                        "WHERE id=? AND status='RUNNING' AND locked_by=? AND version=?",
                task.getId(), task.getLockedBy(), task.getVersion()) == 1;
    }

    public FailureDisposition markFailed(ReliableTask task, String error) {
        int nextRetryCount = task.getRetryCount() + 1;
        String safeError = error == null ? "unknown" : error.substring(0, Math.min(error.length(), 500));
        if (nextRetryCount >= task.getMaxRetry()) {
            int changed = jdbcTemplate.update(
                    "UPDATE tb_reliable_task SET status='DEAD',retry_count=?,last_error=?," +
                            "locked_by=NULL,lease_until=NULL,update_time=NOW() " +
                            "WHERE id=? AND status='RUNNING' AND locked_by=? AND version=?",
                    nextRetryCount, safeError, task.getId(), task.getLockedBy(), task.getVersion());
            return changed == 1 ? FailureDisposition.DEAD : FailureDisposition.LOST_LEASE;
        }
        int delaySeconds = Math.min(300, 1 << Math.min(nextRetryCount, 8));
        LocalDateTime next = LocalDateTime.now().plusSeconds(delaySeconds);
        int changed = jdbcTemplate.update(
                "UPDATE tb_reliable_task SET status='PENDING',retry_count=?,next_retry_time=?,last_error=?," +
                        "locked_by=NULL,lease_until=NULL,update_time=NOW() " +
                        "WHERE id=? AND status='RUNNING' AND locked_by=? AND version=?",
                nextRetryCount, next, safeError, task.getId(), task.getLockedBy(), task.getVersion());
        return changed == 1 ? FailureDisposition.RETRY : FailureDisposition.LOST_LEASE;
    }

    public boolean replayDead(Long id) {
        return jdbcTemplate.update(
                "UPDATE tb_reliable_task SET status='PENDING',retry_count=0,next_retry_time=NOW()," +
                        "last_error=NULL,locked_by=NULL,lease_until=NULL,version=version+1,update_time=NOW() " +
                        "WHERE id=? AND status='DEAD'", id) == 1;
    }

    public long countByStatus(String status) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_reliable_task WHERE status=?", Long.class, status);
        return count == null ? 0L : count;
    }

    /** Tasks that a worker could claim now, including abandoned RUNNING leases. */
    public long countReady() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_reliable_task " +
                        "WHERE (status='PENDING' AND next_retry_time<=NOW()) " +
                        "OR (status='RUNNING' AND lease_until<=NOW())", Long.class);
        return count == null ? 0L : count;
    }

    /** Age is measured from the due/lease-expiry time, so future timeout tasks do not look like backlog. */
    public long oldestReadyAgeSeconds() {
        Long age = jdbcTemplate.queryForObject(
                "SELECT COALESCE(TIMESTAMPDIFF(SECOND,MIN(" +
                        "CASE WHEN status='PENDING' THEN next_retry_time ELSE lease_until END),NOW()),0) " +
                        "FROM tb_reliable_task WHERE (status='PENDING' AND next_retry_time<=NOW()) " +
                        "OR (status='RUNNING' AND lease_until<=NOW())", Long.class);
        return age == null ? 0L : Math.max(0L, age);
    }
}
