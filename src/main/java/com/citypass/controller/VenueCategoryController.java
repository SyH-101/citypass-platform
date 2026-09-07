package com.citypass.controller;


import com.citypass.dto.Result;
import com.citypass.entity.VenueCategory;
import com.citypass.service.IVenueCategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 */
@RestController
@RequestMapping("/venue-categories")
public class VenueCategoryController {
    @Resource
    private IVenueCategoryService typeService;

    @GetMapping("list")
    public Result queryTypeList() {
        List<VenueCategory> typeList = typeService
                .query().orderByAsc("sort").list();
        return Result.ok(typeList);
    }
}
