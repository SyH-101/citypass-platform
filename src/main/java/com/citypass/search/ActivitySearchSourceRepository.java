package com.citypass.search;

import com.citypass.dto.ActivitySearchMetadataRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class ActivitySearchSourceRepository {
    private static final String SOURCE_COLUMNS =
            "p.id activity_id,p.venue_id,p.title,p.sub_title,p.description,p.rules," +
            "p.activity_category,p.tags,p.event_start_time,p.event_end_time,p.pay_value price_cents," +
            "p.type pass_type,p.status,p.search_version,v.name venue_name,v.area,v.address," +
            "v.x longitude,v.y latitude ";

    private static final RowMapper<ActivitySearchSource> SOURCE_MAPPER = (rs, rowNum) -> {
        ActivitySearchSource source = new ActivitySearchSource();
        source.setActivityId(rs.getLong("activity_id"));
        source.setVenueId(nullableLong(rs, "venue_id"));
        source.setTitle(rs.getString("title"));
        source.setSubTitle(rs.getString("sub_title"));
        source.setDescription(rs.getString("description"));
        source.setRules(rs.getString("rules"));
        source.setActivityCategory(rs.getString("activity_category"));
        source.setTags(rs.getString("tags"));
        Timestamp start = rs.getTimestamp("event_start_time");
        Timestamp end = rs.getTimestamp("event_end_time");
        source.setEventStartTime(start == null ? null : start.toLocalDateTime());
        source.setEventEndTime(end == null ? null : end.toLocalDateTime());
        source.setPriceCents(nullableLong(rs, "price_cents"));
        source.setPassType(nullableInteger(rs, "pass_type"));
        source.setStatus(nullableInteger(rs, "status"));
        source.setSearchVersion(rs.getLong("search_version"));
        source.setVenueName(rs.getString("venue_name"));
        source.setArea(rs.getString("area"));
        source.setAddress(rs.getString("address"));
        source.setLongitude(nullableDouble(rs, "longitude"));
        source.setLatitude(nullableDouble(rs, "latitude"));
        return source;
    };

    private final JdbcTemplate jdbcTemplate;

    public ActivitySearchSourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ActivitySearchSource findById(Long id) {
        List<ActivitySearchSource> rows = jdbcTemplate.query(
                "SELECT " + SOURCE_COLUMNS + "FROM tb_activity_pass p LEFT JOIN tb_venue v ON v.id=p.venue_id WHERE p.id=?",
                new Object[]{id}, SOURCE_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ActivitySearchSource> findBatchAfter(Long afterId, int limit) {
        return jdbcTemplate.query(
                "SELECT " + SOURCE_COLUMNS + "FROM tb_activity_pass p LEFT JOIN tb_venue v ON v.id=p.venue_id " +
                        "WHERE p.id>? ORDER BY p.id LIMIT ?",
                new Object[]{afterId, limit}, SOURCE_MAPPER);
    }

    public long countAll() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_activity_pass", Long.class);
        return count == null ? 0L : count;
    }

    public int updateMetadata(Long id, ActivitySearchMetadataRequest request) {
        return jdbcTemplate.update(
                "UPDATE tb_activity_pass SET title=?,sub_title=?,description=?,activity_category=?,tags=?," +
                        "event_start_time=?,event_end_time=?,search_version=search_version+1 WHERE id=?",
                request.getTitle().trim(), trimToNull(request.getSubTitle()), trimToNull(request.getDescription()),
                request.getActivityCategory().trim().toUpperCase(Locale.ROOT), trimToNull(request.getTags()),
                request.getEventStartTime(), request.getEventEndTime(), id);
    }

    public int updateStatus(Long id, int status) {
        return jdbcTemplate.update(
                "UPDATE tb_activity_pass SET status=?,search_version=search_version+1 WHERE id=? AND status<>?",
                status, id, status);
    }

    public Long findVersion(Long id) {
        List<Long> versions = jdbcTemplate.query(
                "SELECT search_version FROM tb_activity_pass WHERE id=?",
                new Object[]{id}, (rs, rowNum) -> rs.getLong(1));
        return versions.isEmpty() ? null : versions.get(0);
    }

    public List<ActivitySearchVersion> bumpVenueDocuments(Long venueId) {
        jdbcTemplate.update("UPDATE tb_activity_pass SET search_version=search_version+1 WHERE venue_id=?", venueId);
        return jdbcTemplate.query(
                "SELECT id activity_id,search_version FROM tb_activity_pass WHERE venue_id=? ORDER BY id",
                new Object[]{venueId},
                (rs, rowNum) -> new ActivitySearchVersion(rs.getLong("activity_id"), rs.getLong("search_version")));
    }

    public Map<Long, Integer> findCurrentStatuses(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyMap();
        String marks = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(ids);
        return jdbcTemplate.query(
                "SELECT id,status FROM tb_activity_pass WHERE id IN (" + marks + ")",
                args.toArray(),
                rs -> {
                    Map<Long, Integer> statuses = new HashMap<>();
                    while (rs.next()) {
                        // Connector/J 会把 TINYINT(1) 暴露成 Boolean；按 JDBC 数值读取才能区分业务状态值。
                        statuses.put(rs.getLong("id"), rs.getInt("status"));
                    }
                    return statuses;
                });
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInteger(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }
}
