package com.citypass.search;

import com.citypass.config.ActivitySearchProperties;
import com.citypass.dto.ActivitySearchItem;
import com.citypass.dto.ActivitySearchPage;
import com.citypass.dto.ActivitySearchRequest;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.search.ClosePointInTimeRequest;
import org.elasticsearch.action.search.OpenPointInTimeRequest;
import org.elasticsearch.action.search.OpenPointInTimeResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.PointInTimeBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ActivitySearchService {
    private final ActivitySearchProperties properties;
    private final ActivitySearchIndexService indexService;
    private final ActivitySearchRequestValidator validator;
    private final ActivitySearchCursorCodec cursorCodec;
    private final ActivitySearchSourceRepository sourceRepository;
    private final ActivitySearchMetrics metrics;
    private final ZoneId zoneId = ZoneId.systemDefault();

    public ActivitySearchService(ActivitySearchProperties properties,
                                 ActivitySearchIndexService indexService,
                                 ActivitySearchRequestValidator validator,
                                 ActivitySearchCursorCodec cursorCodec,
                                 ActivitySearchSourceRepository sourceRepository,
                                 ActivitySearchMetrics metrics) {
        this.properties = properties;
        this.indexService = indexService;
        this.validator = validator;
        this.cursorCodec = cursorCodec;
        this.sourceRepository = sourceRepository;
        this.metrics = metrics;
    }

    public ActivitySearchPage search(ActivitySearchRequest rawRequest) {
        try {
            return metrics.record(() -> doSearch(rawRequest));
        } catch (IllegalArgumentException | SearchModuleUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("活动搜索调用 Elasticsearch 失败: {}", e.toString(), e);
            throw new SearchModuleUnavailableException("活动搜索暂不可用，请稍后重试");
        }
    }

    public void closeCursor(String encodedCursor) {
        ActivitySearchCursor cursor = cursorCodec.decode(encodedCursor);
        closePitQuietly(cursor.getPitId());
    }

    SearchSourceBuilder buildQuery(NormalizedActivitySearchRequest request, long snapshotEpochMillis,
                                   List<Object> searchAfter, String pitId) {
        BoolQueryBuilder query = QueryBuilders.boolQuery()
                .filter(QueryBuilders.termQuery("searchable", true))
                .filter(QueryBuilders.rangeQuery("eventEndTime").gte(snapshotEpochMillis));
        if (request.getKeyword() != null) {
            query.must(QueryBuilders.multiMatchQuery(request.getKeyword())
                    .field("title", 4.0f)
                    .field("subTitle", 2.0f)
                    .field("tags", 2.0f)
                    .field("venueName", 1.5f)
                    .field("description", 1.0f)
                    .field("rules", 0.3f));
        } else {
            query.must(QueryBuilders.matchAllQuery());
        }
        if (request.getCategory() != null) {
            query.filter(QueryBuilders.termQuery("activityCategory", request.getCategory()));
        }
        // Event intervals intersect the requested closed interval: eventEnd >= from AND eventStart <= to.
        if (request.getEventFrom() != null) {
            query.filter(QueryBuilders.rangeQuery("eventEndTime").gte(toMillis(request.getEventFrom())));
        }
        if (request.getEventTo() != null) {
            query.filter(QueryBuilders.rangeQuery("eventStartTime").lte(toMillis(request.getEventTo())));
        }
        if (request.getMinPriceCents() != null) {
            query.filter(QueryBuilders.rangeQuery("priceCents").gte(request.getMinPriceCents()));
        }
        if (request.getMaxPriceCents() != null) {
            query.filter(QueryBuilders.rangeQuery("priceCents").lte(request.getMaxPriceCents()));
        }
        if (request.getRadiusMeters() != null) {
            query.filter(QueryBuilders.geoDistanceQuery("location")
                    .point(request.getLatitude(), request.getLongitude())
                    .distance(request.getRadiusMeters(), DistanceUnit.METERS));
        }

        int fetchSize = Math.min(properties.getMaxPageSize() + 1, request.getSize() + 1);
        SearchSourceBuilder source = new SearchSourceBuilder().query(query).size(fetchSize).trackTotalHits(false)
                .pointInTimeBuilder(new PointInTimeBuilder(pitId)
                        .setKeepAlive(TimeValue.timeValueSeconds(properties.getPitKeepAliveSeconds())));
        if (request.getSort() == ActivitySearchSort.RELEVANCE) {
            source.sort(SortBuilders.scoreSort().order(SortOrder.DESC));
        } else if (request.getSort() == ActivitySearchSort.DISTANCE) {
            source.sort(SortBuilders.geoDistanceSort("location", new GeoPoint(request.getLatitude(), request.getLongitude()))
                    .unit(DistanceUnit.METERS).order(SortOrder.ASC));
        }
        source.sort("eventStartTime", SortOrder.ASC).sort("activityId", SortOrder.ASC);
        if (searchAfter != null && !searchAfter.isEmpty()) source.searchAfter(searchAfter.toArray());
        return source;
    }

    private ActivitySearchPage doSearch(ActivitySearchRequest rawRequest) throws Exception {
        if (!indexService.isEnabled()) throw new SearchModuleUnavailableException("活动搜索模块未启用");
        NormalizedActivitySearchRequest request = validator.validate(rawRequest);
        ActivitySearchCursor previousCursor = null;
        String pitId;
        long snapshotEpochMillis;
        List<Object> searchAfter = null;
        boolean openedHere = false;
        if (request.getCursor() == null) {
            snapshotEpochMillis = System.currentTimeMillis();
            pitId = openPit();
            openedHere = true;
        } else {
            previousCursor = cursorCodec.decode(request.getCursor());
            if (previousCursor.getExpiresAtEpochMillis() <= System.currentTimeMillis()) {
                closePitQuietly(previousCursor.getPitId());
                throw new SearchCursorExpiredException("搜索游标已过期，请从第一页重新搜索");
            }
            snapshotEpochMillis = previousCursor.getSnapshotEpochMillis();
            String expected = cursorCodec.fingerprint(request, snapshotEpochMillis);
            if (!Objects.equals(expected, previousCursor.getQueryFingerprint())) {
                throw new IllegalArgumentException("搜索条件、排序或页大小与游标不匹配");
            }
            pitId = previousCursor.getPitId();
            searchAfter = previousCursor.getSortValues();
        }

        try {
            List<ActivitySearchItem> results = new ArrayList<>(request.getSize());
            List<Object> lastScanned = searchAfter;
            boolean hasMore = false;
            int batches = 0;
            String latestPitId = pitId;
            while (results.size() < request.getSize() && batches++ < Math.max(1, properties.getMaxSupplementBatches())) {
                SearchRequest esRequest = new SearchRequest();
                esRequest.source(buildQuery(request, snapshotEpochMillis, lastScanned, latestPitId));
                SearchResponse response = indexService.client().search(esRequest, RequestOptions.DEFAULT);
                if (response.pointInTimeId() != null) latestPitId = response.pointInTimeId();
                SearchHit[] hits = response.getHits().getHits();
                if (hits.length == 0) {
                    hasMore = false;
                    break;
                }
                List<Long> ids = java.util.Arrays.stream(hits)
                        .map(hit -> number(hit.getSourceAsMap().get("activityId")).longValue()).collect(Collectors.toList());
                Map<Long, Integer> currentStatuses = sourceRepository.findCurrentStatuses(ids);
                int consumed = 0;
                for (SearchHit hit : hits) {
                    consumed++;
                    lastScanned = java.util.Arrays.asList(hit.getSortValues());
                    Long id = number(hit.getSourceAsMap().get("activityId")).longValue();
                    if (Integer.valueOf(1).equals(currentStatuses.get(id))) results.add(toItem(hit, request));
                    if (results.size() == request.getSize()) {
                        hasMore = consumed < hits.length || hits.length == request.getSize() + 1;
                        break;
                    }
                }
                if (results.size() == request.getSize()) break;
                if (hits.length < request.getSize() + 1) {
                    hasMore = false;
                    break;
                }
                hasMore = true;
            }

            if (!hasMore || lastScanned == null) {
                closePitQuietly(latestPitId);
                return new ActivitySearchPage(results, null, false);
            }
            ActivitySearchCursor next = new ActivitySearchCursor();
            next.setPitId(latestPitId);
            next.setSortValues(lastScanned);
            next.setSnapshotEpochMillis(snapshotEpochMillis);
            next.setExpiresAtEpochMillis(System.currentTimeMillis()
                    + properties.getPitKeepAliveSeconds() * 1000L);
            next.setQueryFingerprint(cursorCodec.fingerprint(request, snapshotEpochMillis));
            return new ActivitySearchPage(results, cursorCodec.encode(next), true);
        } catch (ElasticsearchException e) {
            if (isExpiredPit(e)) throw new SearchCursorExpiredException("搜索游标已过期，请从第一页重新搜索");
            if (openedHere) closePitQuietly(pitId);
            throw e;
        } catch (Exception e) {
            if (isExpiredPit(e)) throw new SearchCursorExpiredException("搜索游标已过期，请从第一页重新搜索");
            if (openedHere) closePitQuietly(pitId);
            throw e;
        }
    }

    private String openPit() throws Exception {
        OpenPointInTimeRequest request = new OpenPointInTimeRequest(indexService.alias());
        request.keepAlive(TimeValue.timeValueSeconds(properties.getPitKeepAliveSeconds()));
        OpenPointInTimeResponse response = indexService.client().openPointInTime(request, RequestOptions.DEFAULT);
        return response.getPointInTimeId();
    }

    private void closePitQuietly(String pitId) {
        if (pitId == null || !indexService.isEnabled()) return;
        try {
            indexService.client().closePointInTime(new ClosePointInTimeRequest(pitId), RequestOptions.DEFAULT);
        } catch (Exception ignored) {
            // PIT has a bounded keep-alive; explicit close is best effort.
        }
    }

    private boolean isExpiredPit(Exception e) {
        String text = String.valueOf(e.getMessage());
        return text.contains("search_context_missing_exception") || text.contains("No search context found");
    }

    @SuppressWarnings("unchecked")
    private ActivitySearchItem toItem(SearchHit hit, NormalizedActivitySearchRequest request) {
        Map<String, Object> source = hit.getSourceAsMap();
        ActivitySearchItem item = new ActivitySearchItem();
        item.setActivityId(number(source.get("activityId")).longValue());
        item.setVenueId(longValue(source.get("venueId")));
        item.setTitle((String) source.get("title"));
        item.setSubTitle((String) source.get("subTitle"));
        item.setDescription((String) source.get("description"));
        item.setActivityCategory((String) source.get("activityCategory"));
        Object tags = source.get("tags");
        item.setTags(tags instanceof List ? (List<String>) tags : Collections.emptyList());
        item.setPassType(integerValue(source.get("passType")));
        item.setEventStartTime(localDateTime(source.get("eventStartTime")));
        item.setEventEndTime(localDateTime(source.get("eventEndTime")));
        item.setPriceCents(longValue(source.get("priceCents")));
        item.setVenueName((String) source.get("venueName"));
        item.setArea((String) source.get("area"));
        item.setAddress((String) source.get("address"));
        Object location = source.get("location");
        if (location instanceof Map) {
            Map<String, Object> point = (Map<String, Object>) location;
            item.setLongitude(doubleValue(point.get("lon")));
            item.setLatitude(doubleValue(point.get("lat")));
        }
        if (request.getSort() == ActivitySearchSort.DISTANCE && hit.getSortValues().length > 0) {
            item.setDistanceMeters(number(hit.getSortValues()[0]).doubleValue());
        }
        return item;
    }

    private long toMillis(LocalDateTime value) {
        return value.atZone(zoneId).toInstant().toEpochMilli();
    }

    private LocalDateTime localDateTime(Object value) {
        if (value == null) return null;
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(number(value).longValue()), zoneId);
    }

    private Number number(Object value) {
        if (!(value instanceof Number)) throw new IllegalStateException("搜索文档数值字段无效");
        return (Number) value;
    }

    private Long longValue(Object value) { return value == null ? null : number(value).longValue(); }
    private Integer integerValue(Object value) { return value == null ? null : number(value).intValue(); }
    private Double doubleValue(Object value) { return value == null ? null : number(value).doubleValue(); }
}
