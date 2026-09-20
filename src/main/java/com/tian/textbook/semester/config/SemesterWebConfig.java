package com.tian.textbook.semester.config;

import com.tian.textbook.semester.context.SemesterContextInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册学期上下文拦截器（/api/** 与页面路径均解析 active 学期快照）。
 */
@Component
@RequiredArgsConstructor
public class SemesterWebConfig implements WebMvcConfigurer {

    private final SemesterContextInterceptor semesterContextInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(semesterContextInterceptor)
                .addPathPatterns("/api/**");
    }
}
