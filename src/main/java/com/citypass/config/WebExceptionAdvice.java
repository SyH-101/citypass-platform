package com.citypass.config;

import com.citypass.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.citypass.utils.CacheDegradedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(com.citypass.story.StoryProblem.class)
    public org.springframework.http.ResponseEntity<Result> handleStoryProblem(
            com.citypass.story.StoryProblem exception) {
        return org.springframework.http.ResponseEntity.status(exception.getStatus())
                .body(Result.fail(exception.getMessage()));
    }

    @ExceptionHandler(CacheDegradedException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result handleCacheDegraded(CacheDegradedException exception) {
        log.warn(exception.getMessage());
        return Result.fail(exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return Result.fail("请求参数格式错误: " + exception.getName());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result handleIllegalArgumentException(IllegalArgumentException exception) {
        return Result.fail(exception.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException exception) {
        log.error(exception.toString(), exception);
        return Result.fail("服务器异常");
    }
}
