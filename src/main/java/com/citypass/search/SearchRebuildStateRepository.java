package com.citypass.search;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class SearchRebuildStateRepository {
    private final JdbcTemplate jdbcTemplate;

    public SearchRebuildStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Locks the singleton row until the caller's business transaction commits. */
    public void assertWritesAllowed() {
        Boolean blocked = jdbcTemplate.queryForObject(
                "SELECT write_blocked FROM tb_search_rebuild_state WHERE id=1 FOR UPDATE", Boolean.class);
        if (Boolean.TRUE.equals(blocked)) {
            throw new IllegalArgumentException("活动搜索索引正在重建，活动及场馆搜索字段暂不可修改");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void begin(String targetIndex, String previousIndex) {
        SearchRebuildState state = getForUpdate();
        if (state != null && "RUNNING".equals(state.getStatus())) {
            throw new IllegalStateException("已有活动搜索索引重建正在运行");
        }
        jdbcTemplate.update("UPDATE tb_search_rebuild_state SET status='RUNNING',write_blocked=1," +
                        "target_index=?,previous_index=?,source_count=0,indexed_count=0,last_error=NULL," +
                        "started_at=NOW(),finished_at=NULL WHERE id=1", targetIndex, previousIndex);
    }

    public void progress(long sourceCount, long indexedCount) {
        jdbcTemplate.update("UPDATE tb_search_rebuild_state SET source_count=?,indexed_count=? WHERE id=1",
                sourceCount, indexedCount);
    }

    public void previousIndex(String previousIndex) {
        jdbcTemplate.update("UPDATE tb_search_rebuild_state SET previous_index=? WHERE id=1", previousIndex);
    }

    public void succeed(long sourceCount, long indexedCount) {
        jdbcTemplate.update("UPDATE tb_search_rebuild_state SET status='SUCCEEDED',write_blocked=0," +
                "source_count=?,indexed_count=?,last_error=NULL,finished_at=NOW() WHERE id=1",
                sourceCount, indexedCount);
    }

    public void fail(String message, long sourceCount, long indexedCount) {
        String safe = message == null ? "unknown" : message.substring(0, Math.min(500, message.length()));
        jdbcTemplate.update("UPDATE tb_search_rebuild_state SET status='FAILED',write_blocked=0," +
                "source_count=?,indexed_count=?,last_error=?,finished_at=NOW() WHERE id=1",
                sourceCount, indexedCount, safe);
    }

    public SearchRebuildState get() {
        List<SearchRebuildState> states = jdbcTemplate.query(
                "SELECT * FROM tb_search_rebuild_state WHERE id=1", (rs, rowNum) -> map(rs));
        return states.isEmpty() ? null : states.get(0);
    }

    private SearchRebuildState getForUpdate() {
        List<SearchRebuildState> states = jdbcTemplate.query(
                "SELECT * FROM tb_search_rebuild_state WHERE id=1 FOR UPDATE", (rs, rowNum) -> map(rs));
        return states.isEmpty() ? null : states.get(0);
    }

    private SearchRebuildState map(java.sql.ResultSet rs) throws java.sql.SQLException {
        SearchRebuildState state = new SearchRebuildState();
        state.setId(rs.getInt("id"));
        state.setStatus(rs.getString("status"));
        state.setWriteBlocked(rs.getBoolean("write_blocked"));
        state.setTargetIndex(rs.getString("target_index"));
        state.setPreviousIndex(rs.getString("previous_index"));
        state.setSourceCount(rs.getLong("source_count"));
        state.setIndexedCount(rs.getLong("indexed_count"));
        state.setLastError(rs.getString("last_error"));
        state.setStartedAt(toLocal(rs.getTimestamp("started_at")));
        state.setFinishedAt(toLocal(rs.getTimestamp("finished_at")));
        return state;
    }

    private LocalDateTime toLocal(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
