package com.citypass.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.citypass.dto.Result;
import com.citypass.entity.Venue;
import com.citypass.service.IVenueService;
import com.citypass.utils.SystemConstants;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 */
@RestController
@RequestMapping("/venues")
public class VenueController {

    @Resource
    public IVenueService venueService;

    /**
     * 根据id查询场馆信息
     * @param id 场馆id
     * @return 场馆详情数据
     */
    @GetMapping("/{id}")
    public Result queryVenueById(@PathVariable("id") Long id) {
        return venueService.queryById(id);
    }

    /**
     * 读链路压测基线：Redis → MySQL（不含 Caffeine / 网关 L1）。
     */
    @GetMapping("/benchmark/redis/{id}")
    public Result queryVenueByIdRedisBaseline(@PathVariable("id") Long id) {
        return venueService.queryByIdRedisBaseline(id);
    }

    /**
     * 读链路压测对照：直查 MySQL（仅辅助）
     */
    @GetMapping("/benchmark/db/{id}")
    public Result queryVenueByIdFromDb(@PathVariable("id") Long id) {
        Venue venue = venueService.getById(id);
        if (venue == null) {
            return Result.fail("场馆不存在！");
        }
        return Result.ok(venue);
    }

    /**
     * 新增场馆信息
     * @param venue 场馆数据
     * @return 场馆id
     */
    @PostMapping
    public Result saveVenue(@RequestBody Venue venue) {
        // 写入数据库
        venueService.save(venue);
        // 返回场馆id
        return Result.ok(venue.getId());
    }

    /**
     * 更新场馆信息
     * @param venue 场馆数据
     * @return 无
     */
    @PutMapping
    public Result updateVenue(@RequestBody Venue venue) {
        // 写入数据库
        return venueService.update(venue);
    }

    /**
     * 根据场馆类型分页查询场馆信息
     * @param categoryId 场馆类型
     * @param current 页码
     * @return 场馆列表
     */
    @GetMapping("/of/type")
    public Result queryVenueByType(
            @RequestParam("categoryId") Integer categoryId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "x", required = false) Double x,
            @RequestParam(value = "y", required = false) Double y
    ) {
       return venueService.queryVenueByType(categoryId, current, x, y);
    }

    /**
     * 根据场馆名称关键字分页查询场馆信息
     * @param name 场馆名称关键字
     * @param current 页码
     * @return 场馆列表
     */
    @GetMapping("/of/name")
    public Result queryVenueByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        // 根据类型分页查询
        Page<Venue> page = venueService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 返回数据
        return Result.ok(page.getRecords());
    }
}
