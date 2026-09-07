package com.citypass;

import com.citypass.entity.Venue;
import com.citypass.service.impl.VenueServiceImpl;
import com.citypass.utils.CacheClient;
import com.citypass.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.citypass.utils.RedisConstants.CACHE_VENUE_KEY;
import static com.citypass.utils.RedisConstants.VENUE_GEO_KEY;

@SpringBootTest
@Disabled("手工基础设施测试：需要 MySQL 和 Redis，正常单测不自动运行")
class CityPassApplicationTests {

    @Resource
    private CacheClient cacheClient;

    @Resource
    private VenueServiceImpl venueService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

    @Test
    void testSaveVenue() throws InterruptedException {
        Venue venue = venueService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_VENUE_KEY + 1L, venue, 10L, TimeUnit.SECONDS);
    }

    @Test
    void loadVenueData() {
        // 1.查询场馆信息
        List<Venue> list = venueService.list();
        // 2.把场馆分组，按照categoryId分组，categoryId一致的放到一个集合
        Map<Long, List<Venue>> map = list.stream().collect(Collectors.groupingBy(Venue::getCategoryId));
        // 3.分批完成写入Redis
        for (Map.Entry<Long, List<Venue>> entry : map.entrySet()) {
            // 3.1.获取类型id
            Long categoryId = entry.getKey();
            String key = VENUE_GEO_KEY + categoryId;
            // 3.2.获取同类型的场馆的集合
            List<Venue> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            // 3.3.写入redis GEOADD key 经度 纬度 member
            for (Venue venue : value) {
                // stringRedisTemplate.opsForGeo().add(key, new Point(venue.getX(), venue.getY()), venue.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        venue.getId().toString(),
                        new Point(venue.getX(), venue.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    @Test
    void testHyperLogLog() {
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if(j == 999){
                // 发送到Redis
                stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
            }
        }
        // 统计数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println("count = " + count);
    }
}
