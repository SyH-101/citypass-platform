package com.citypass.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.citypass.dto.Result;
import com.citypass.entity.Venue;
import com.citypass.mapper.VenueMapper;
import com.citypass.service.IVenueService;
import com.citypass.utils.CacheClient;
import com.citypass.utils.MultiLevelCacheService;
import com.citypass.reliable.ReliableTaskRepository;
import com.citypass.reliable.VenueCacheInvalidation;
import cn.hutool.json.JSONUtil;
import com.citypass.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.citypass.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @since 2021-12-22
 */
@Service
public class VenueServiceImpl extends ServiceImpl<VenueMapper, Venue> implements IVenueService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private MultiLevelCacheService multiLevelCache;

    @Resource
    private ReliableTaskRepository reliableTaskRepository;

    @Override
    public Result queryById(Long id) {
        // 多级缓存：Caffeine(L1 JVM) → Redis逻辑过期(L2) → MySQL(L3)
        Venue venue = multiLevelCache
                .queryWithMultiLevel(CACHE_VENUE_KEY, id, Venue.class, this::getById, CACHE_VENUE_TTL, TimeUnit.MINUTES);

        if (venue == null) {
            return Result.fail("场馆不存在！");
        }
        return Result.ok(venue);
    }

    /**
     * 读路径压测基线：仅 Redis → MySQL（无 Caffeine / 无网关 L1）。
     */
    @Override
    public Result queryByIdRedisBaseline(Long id) {
        Venue venue = cacheClient
                .queryWithPassThrough(CACHE_VENUE_BASELINE_KEY, id, Venue.class, this::getById, CACHE_VENUE_TTL, TimeUnit.MINUTES);
        if (venue == null) {
            return Result.fail("场馆不存在！");
        }
        return Result.ok(venue);
    }

    @Override
    @Transactional
    public Result update(Venue venue) {
        Long id = venue.getId();
        if (id == null) {
            return Result.fail("场馆id不能为空");
        }
        // 1.更新数据库
        boolean updated = updateById(venue);
        if (!updated) {
            return Result.fail("场馆不存在或更新失败");
        }
        if (getBaseMapper().incrementCacheVersion(id) != 1) {
            throw new IllegalStateException("场馆缓存版本更新失败");
        }
        Venue refreshed = getById(id);
        reliableTaskRepository.enqueue(
                ReliableTaskRepository.INVALIDATE_VENUE_CACHE,
                "invalidate-venue-cache:" + id + ":" + refreshed.getCacheVersion(),
                JSONUtil.toJsonStr(new VenueCacheInvalidation(id, refreshed.getCacheVersion())));
        return Result.ok();
    }

    @Override
    public Result queryVenueByType(Integer categoryId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Venue> page = query()
                    .eq("category_id", categoryId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：venueId、distance
        String key = VENUE_GEO_KEY + categoryId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取场馆id
            String venueIdStr = result.getContent().getName();
            ids.add(Long.valueOf(venueIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(venueIdStr, distance);
        });
        // 5.根据id查询Venue
        String idStr = StrUtil.join(",", ids);
        List<Venue> venues = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Venue venue : venues) {
            venue.setDistance(distanceMap.get(venue.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(venues);
    }
}
