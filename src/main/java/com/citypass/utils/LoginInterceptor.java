package com.citypass.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.判断是否需要拦截（ThreadLocal中是否有用户）
        if (UserHolder.getUser() == null) {
            if (isPublicRead(request)) {
                return true;
            }
            // 没有，需要拦截，设置状态码
            response.setStatus(401);
            // 拦截
            return false;
        }
        // 有用户，则放行
        return true;
    }

    private boolean isPublicRead(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        return uri.startsWith("/venues/")
                || uri.startsWith("/passes/")
                || uri.startsWith("/venue-categories/")
                || uri.matches("/stories/\\d+")
                || uri.startsWith("/stories/likes/")
                || uri.equals("/stories/of/user")
                || uri.startsWith("/story-comments/story/");
    }
}
