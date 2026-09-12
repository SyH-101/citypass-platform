package com.citypass.search;

import cn.hutool.json.JSONUtil;
import com.citypass.config.ActivitySearchProperties;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest;
import org.elasticsearch.client.GetAliasesResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.rest.RestStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ActivitySearchIndexService {
    private final ActivitySearchProperties properties;
    private final ObjectProvider<RestHighLevelClient> clientProvider;

    public ActivitySearchIndexService(ActivitySearchProperties properties,
                                      ObjectProvider<RestHighLevelClient> clientProvider) {
        this.properties = properties;
        this.clientProvider = clientProvider;
    }

    public boolean isEnabled() {
        return properties.isEnabled() && clientProvider.getIfAvailable() != null;
    }

    RestHighLevelClient client() {
        RestHighLevelClient client = clientProvider.getIfAvailable();
        if (!properties.isEnabled() || client == null) {
            throw new SearchModuleUnavailableException("活动搜索模块未启用");
        }
        return client;
    }

    public String alias() {
        return properties.getAlias();
    }

    public void indexToAlias(ActivitySearchDocument document) throws IOException {
        if (aliasTargets().isEmpty()) {
            throw new IllegalStateException("活动搜索别名尚未初始化，请先执行全量重建");
        }
        indexDocument(properties.getAlias(), document);
    }

    public void indexDocument(String target, ActivitySearchDocument document) throws IOException {
        IndexRequest request = new IndexRequest(target)
                .id(String.valueOf(document.getActivityId()))
                .source(JSONUtil.toJsonStr(source(document)), XContentType.JSON)
                .version(document.getSearchVersion())
                .versionType(VersionType.EXTERNAL_GTE);
        try {
            client().index(request, RequestOptions.DEFAULT);
        } catch (ElasticsearchStatusException e) {
            if (e.status() != RestStatus.CONFLICT || currentVersion(target, document.getActivityId()) < document.getSearchVersion()) {
                throw e;
            }
            // An older snapshot lost to a newer external version. This is a successful stale no-op.
        }
    }

    public void bulkIndex(String target, List<ActivitySearchDocument> documents) throws IOException {
        if (documents.isEmpty()) return;
        BulkRequest request = new BulkRequest();
        for (ActivitySearchDocument document : documents) {
            request.add(new IndexRequest(target)
                    .id(String.valueOf(document.getActivityId()))
                    .source(JSONUtil.toJsonStr(source(document)), XContentType.JSON)
                    .version(document.getSearchVersion())
                    .versionType(VersionType.EXTERNAL_GTE));
        }
        BulkResponse response = client().bulk(request, RequestOptions.DEFAULT);
        if (response.hasFailures()) {
            List<String> failures = new ArrayList<>();
            for (BulkItemResponse item : response.getItems()) {
                if (item.isFailed()) failures.add(item.getId() + ":" + item.getFailureMessage());
                if (failures.size() >= 3) break;
            }
            throw new IllegalStateException("活动索引批量写入存在失败项: " + String.join(";", failures));
        }
    }

    public void createPhysicalIndex(String index) throws IOException {
        CreateIndexRequest request = new CreateIndexRequest(index);
        request.settings(Settings.builder()
                .put("index.number_of_shards", 1)
                .put("index.number_of_replicas", 0));
        request.mapping(mapping());
        client().indices().create(request, RequestOptions.DEFAULT);
    }

    public Set<String> aliasTargets() throws IOException {
        GetAliasesRequest request = new GetAliasesRequest(properties.getAlias());
        if (!client().indices().existsAlias(request, RequestOptions.DEFAULT)) return Collections.emptySet();
        GetAliasesResponse response = client().indices().getAlias(request, RequestOptions.DEFAULT);
        return response.getAliases().keySet();
    }

    public void switchAlias(String newIndex, Set<String> oldIndexes) throws IOException {
        IndicesAliasesRequest request = new IndicesAliasesRequest();
        for (String old : oldIndexes) {
            request.addAliasAction(new IndicesAliasesRequest.AliasActions(IndicesAliasesRequest.AliasActions.Type.REMOVE)
                    .index(old).alias(properties.getAlias()));
        }
        request.addAliasAction(new IndicesAliasesRequest.AliasActions(IndicesAliasesRequest.AliasActions.Type.ADD)
                .index(newIndex).alias(properties.getAlias()));
        client().indices().updateAliases(request, RequestOptions.DEFAULT);
    }

    public long count(String index) throws IOException {
        org.elasticsearch.client.core.CountRequest request = new org.elasticsearch.client.core.CountRequest(index);
        return client().count(request, RequestOptions.DEFAULT).getCount();
    }

    public void refresh(String index) throws IOException {
        client().indices().refresh(new RefreshRequest(index), RequestOptions.DEFAULT);
    }

    public void deleteInactiveIndex(String index) throws IOException {
        if (index == null || !index.startsWith(properties.getAlias() + "-v")) {
            throw new IllegalArgumentException("只能清理活动搜索模块创建的物理索引");
        }
        if (aliasTargets().contains(index)) throw new IllegalArgumentException("不能删除当前别名指向的索引");
        client().indices().delete(new DeleteIndexRequest(index), RequestOptions.DEFAULT);
    }

    private long currentVersion(String index, Long id) throws IOException {
        GetResponse response = client().get(new GetRequest(index, String.valueOf(id)), RequestOptions.DEFAULT);
        return response.isExists() ? response.getVersion() : -1L;
    }

    private XContentBuilder mapping() throws IOException {
        XContentBuilder builder = XContentFactory.jsonBuilder();
        builder.startObject()
                .field("dynamic", "strict")
                .startObject("properties");
        keyword(builder, "activityId", "long");
        keyword(builder, "venueId", "long");
        text(builder, "title");
        text(builder, "subTitle");
        text(builder, "description");
        text(builder, "rules");
        keyword(builder, "activityCategory", "keyword");
        text(builder, "tags");
        keyword(builder, "eventStartTime", "date");
        keyword(builder, "eventEndTime", "date");
        keyword(builder, "priceCents", "long");
        keyword(builder, "passType", "integer");
        keyword(builder, "status", "integer");
        keyword(builder, "searchable", "boolean");
        keyword(builder, "searchVersion", "long");
        text(builder, "venueName");
        text(builder, "area");
        text(builder, "address");
        keyword(builder, "location", "geo_point");
        builder.endObject().endObject();
        return builder;
    }

    private void text(XContentBuilder builder, String name) throws IOException {
        builder.startObject(name).field("type", "text").field("analyzer", "smartcn")
                .field("search_analyzer", "smartcn").endObject();
    }

    private void keyword(XContentBuilder builder, String name, String type) throws IOException {
        builder.startObject(name).field("type", type).endObject();
    }

    private Map<String, Object> source(ActivitySearchDocument document) {
        Map<String, Object> result = new LinkedHashMap<>();
        put(result, "activityId", document.getActivityId());
        put(result, "venueId", document.getVenueId());
        put(result, "title", document.getTitle());
        put(result, "subTitle", document.getSubTitle());
        put(result, "description", document.getDescription());
        put(result, "rules", document.getRules());
        put(result, "activityCategory", document.getActivityCategory());
        put(result, "tags", document.getTags());
        put(result, "eventStartTime", document.getEventStartTime());
        put(result, "eventEndTime", document.getEventEndTime());
        put(result, "priceCents", document.getPriceCents());
        put(result, "passType", document.getPassType());
        put(result, "status", document.getStatus());
        put(result, "searchable", document.isSearchable());
        put(result, "searchVersion", document.getSearchVersion());
        put(result, "venueName", document.getVenueName());
        put(result, "area", document.getArea());
        put(result, "address", document.getAddress());
        if (document.getLongitude() != null && document.getLatitude() != null) {
            Map<String, Double> point = new LinkedHashMap<>();
            point.put("lat", document.getLatitude());
            point.put("lon", document.getLongitude());
            result.put("location", point);
        }
        return result;
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }
}
