package com.tian.textbook.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;

/**
 * 请求上下文工具（审计 IP、登录限频维度）。
 *
 * <p>可信代理口径：{@code X-Forwarded-For} 只有在直连对端属于
 * {@code textbook.security.trusted-proxies} 白名单时才是可信的。此前无条件信任 XFF 的第一个值，
 * 而 Nginx 用 {@code proxy_add_x_forwarded_for} 追加真实 IP —— 攻击者自填 XFF 即可伪造来源，
 * 既能绕过登录限频，也能把任意已知账号（含超管）锁死 15 分钟（审计日志的 IP 同样不可信）。</p>
 *
 * <p>取值策略：从 XFF 最右侧（最接近服务端、由可信代理写入）开始向左跳过可信代理地址，
 * 第一个非可信地址即客户端真实 IP；若整条链都可信，取最左侧值。无 XFF 时用 remoteAddr。</p>
 */
public final class IpUtils {

    /** 逗号分隔的可信代理地址（IP，不含端口）；为空表示不信任任何 XFF 头 */
    private static volatile String[] trustedProxies = new String[0];

    private IpUtils() {
    }

    /**
     * 注入可信代理白名单（启动时由配置类调用）。
     *
     * @param csv 逗号分隔的 IP 列表，例如 {@code 127.0.0.1,::1}
     */
    public static void configureTrustedProxies(String csv) {
        if (csv == null || csv.isBlank()) {
            trustedProxies = new String[0];
            return;
        }
        trustedProxies = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toArray(String[]::new);
    }

    public static String currentIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        return clientIp(attrs.getRequest());
    }

    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String remoteAddr = request.getRemoteAddr();
        if (!isTrusted(remoteAddr)) {
            // 直连对端不可信：其携带的 XFF 一律忽略（防伪造来源）
            return remoteAddr;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String hop = hops[i].trim();
                if (hop.isEmpty() || "unknown".equalsIgnoreCase(hop)) {
                    continue;
                }
                if (!isTrusted(hop)) {
                    return hop;
                }
            }
            // 整条链都是可信代理：退化为最左侧（最早写入）的值
            String leftmost = hops[0].trim();
            if (!leftmost.isEmpty() && !"unknown".equalsIgnoreCase(leftmost)) {
                return leftmost;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank() && !"unknown".equalsIgnoreCase(realIp)) {
            return realIp.trim();
        }
        return remoteAddr;
    }

    private static boolean isTrusted(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        for (String proxy : trustedProxies) {
            if (proxy.equals(ip)) {
                return true;
            }
        }
        return false;
    }
}
