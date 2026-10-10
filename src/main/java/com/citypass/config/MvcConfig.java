package com.citypass.config;

import com.citypass.utils.LoginInterceptor;
import com.citypass.utils.RefreshTokenInterceptor;
import com.citypass.utils.SlidingWindowInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SlidingWindowInterceptor slidingWindowInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .order(0);
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/stories/hot",
                        "/user/code",
                        "/user/login",
                        "/actuator/**",
                        "/internal/reliable-tasks/**",
                        "/debug/story-files",
                        "/debug/story-files.js",
                        "/debug/story-files.css",
                        "/debug/story-files/**")
                .order(1);
        registry.addInterceptor(slidingWindowInterceptor)
                .addPathPatterns("/reservations/*")
                .order(2);
        registry.addInterceptor(
                        new org.springframework.web.servlet.HandlerInterceptor() {

                            @Override
                            public boolean preHandle(
                                    javax.servlet.http.HttpServletRequest request,
                                    javax.servlet.http.HttpServletResponse response,
                                    Object handler) {
                                response.setHeader("Cache-Control", "private, no-store");
                                return true;
                            }
                        })
                .addPathPatterns("/stories/**")
                .order(-1);
    }
}
