package com.citypass.controller;

import com.citypass.dto.ActivitySearchRequest;
import com.citypass.dto.Result;
import com.citypass.search.ActivitySearchService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/search/activities")
public class ActivitySearchController {
    private final ActivitySearchService searchService;

    public ActivitySearchController(ActivitySearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public Result search(@ModelAttribute ActivitySearchRequest request) {
        return Result.ok(searchService.search(request));
    }

    @DeleteMapping("/cursor")
    public Result closeCursor(@RequestParam String cursor) {
        searchService.closeCursor(cursor);
        return Result.ok();
    }
}
