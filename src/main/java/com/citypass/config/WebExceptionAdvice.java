package com.citypass.config;

import com.citypass.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.citypass.search.SearchModuleUnavailableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.citypass.search.SearchRebuildException;
import com.citypass.utils.CacheDegradedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(CacheDegradedException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result handleCacheDegraded(CacheDegradedException e) {
        log.warn(e.getMessage());
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(SearchModuleUnavailableException.class)
    public Result handleSearchUnavailable(SearchModuleUnavailableException e) {
        log.warn(e.getMessage());
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return Result.fail("请求参数格式错误: " + e.getName());
    }

    @ExceptionHandler(SearchRebuildException.class)
    public Result handleSearchRebuild(SearchRebuildException e) {
        log.error(e.getMessage(), e);
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result handleIllegalArgumentException(IllegalArgumentException e) {
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}
