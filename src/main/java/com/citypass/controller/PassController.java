package com.citypass.controller;


import com.citypass.dto.Result;
import com.citypass.entity.ActivityPass;
import com.citypass.dto.ActivitySearchMetadataRequest;
import com.citypass.service.IActivityPassService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 */
@RestController
@RequestMapping("/passes")
public class PassController {

    @Resource
    private IActivityPassService passService;

    /**
     * 发布限量活动通行证。
     */
    @PostMapping("limited")
    public Result addLimitedPassStock(@RequestBody ActivityPass pass) {
        passService.addLimitedPassStock(pass);
        return Result.ok(pass.getId());
    }

    /**
     * 发布普通活动通行证。
     */
    @PostMapping
    public Result addActivityPass(@RequestBody ActivityPass pass) {
        passService.addActivityPass(pass);
        return Result.ok(pass.getId());
    }


    /**
     * 查询场馆的通行证列表。
     */
    @GetMapping("/venue/{venueId}")
    public Result queryActivityPassOfVenue(@PathVariable("venueId") Long venueId) {
       return passService.queryActivityPassOfVenue(venueId);
    }

    /** Only search-facing metadata can change here; inventory, price and reservation windows are untouched. */
    @PutMapping("/{id}/search-metadata")
    public Result updateSearchMetadata(@PathVariable Long id,
                                       @RequestBody ActivitySearchMetadataRequest request) {
        return passService.updateSearchMetadata(id, request);
    }

    @PutMapping("/{id}/status")
    public Result updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        return passService.updateStatus(id, status);
    }
}
