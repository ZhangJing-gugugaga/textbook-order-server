package com.tian.textbook.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * CORS：Web 走 Nginx 同域反代，小程序端请求不受 CORS 约束；跨域来源必须显式白名单。
 *
 * <p>此前的 {@code addAllowedOriginPattern("*") + setAllowCredentials(true)} 组合
 * 允许任意站点携带凭证跨域读取响应（当前 Bearer 头鉴权降低了直接可利用性，
 * 但前端一旦改为 Cookie 承载 token 即成为致命漏洞）。现改为由
 * {@code textbook.cors.allowed-origins} 显式枚举完整 origin，默认空 = 不返回 CORS 头。</p>
 */
@Slf4j
@Configuration
public class CorsConfig {

    private final TextbookProperties properties;

    public CorsConfig(TextbookProperties properties) {
        this.properties = properties;
    }

    @Bean
    public CorsFilter corsFilter() {
        List<String> origins = parseOrigins(properties.getCors().getAllowedOrigins());
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (origins.isEmpty()) {
            // 无白名单：注册空配置，等价于不追加任何 CORS 响应头（同域请求不受影响）
            log.info("CORS 未配置白名单（textbook.cors.allowed-origins 为空）：不返回跨域响应头");
            source.registerCorsConfiguration("/**", new CorsConfiguration());
            return new CorsFilter(source);
        }
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        origins.forEach(config::addAllowedOrigin);
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setMaxAge(3600L);
        source.registerCorsConfiguration("/**", config);
        log.info("CORS 白名单已生效: {}", origins);
        return new CorsFilter(source);
    }

    /** 逗号分隔 → 去空白去重的完整 origin 列表；拒绝通配符（通配无法与凭证共存）。 */
    private List<String> parseOrigins(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .peek(origin -> {
                    if (origin.contains("*")) {
                        throw new IllegalStateException(
                                "textbook.cors.allowed-origins 不允许通配符: " + origin
                                        + "（请显式枚举完整 origin）");
                    }
                })
                .distinct()
                .toList();
    }
}
