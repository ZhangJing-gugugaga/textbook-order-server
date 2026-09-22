package com.tian.textbook.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求链路标识（P3）：为每个请求生成/透传 traceId，写入 MDC 与响应头。
 *
 * <p>此前日志无任何关联标识，一次前端报错无法把网关访问日志、应用日志、
 * 审计记录串起来（审计表也只记 user/action/ip）。</p>
 *
 * <p>透传规则：请求头已带 {@code X-Request-Id} 且长度合理时沿用它（便于跨服务/网关串联），
 * 否则生成 UUID。响应头回写 {@code X-Request-Id}，前端可在错误提示里带上它。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC 键名（logback pattern 用 %X{traceId} 引用） */
    public static final String MDC_KEY = "traceId";
    /** 请求/响应头名 */
    public static final String HEADER = "X-Request-Id";
    /** 透传 id 的长度上限（防止外部塞入超长值污染日志） */
    private static final int MAX_INBOUND_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 线程池复用：必须清理，否则下一个请求会带上上一个请求的 traceId
            MDC.remove(MDC_KEY);
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String inbound = request.getHeader(HEADER);
        if (inbound != null) {
            String trimmed = inbound.trim();
            if (!trimmed.isEmpty() && trimmed.length() <= MAX_INBOUND_LENGTH
                    && trimmed.chars().allMatch(c -> c >= 0x21 && c < 0x7F)) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
