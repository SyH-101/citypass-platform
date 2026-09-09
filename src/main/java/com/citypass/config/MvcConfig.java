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
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/stories/hot",
                        "/user/code",
                        "/user/login",
                        "/actuator/**",
                        "/internal/reliable-tasks/**"
                ).order(1);
        registry.addInterceptor(slidingWindowInterceptor)
                .addPathPatterns("/reservations/*")
                .order(2);
    }
}
