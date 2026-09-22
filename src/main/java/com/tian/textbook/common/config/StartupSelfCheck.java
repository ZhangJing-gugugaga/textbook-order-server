package com.tian.textbook.common.config;

import com.tian.textbook.common.util.IpUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 启动自检（fail-fast）：把「部署漏配即静默降级」的项在启动期暴露出来，而不是等运行时被触发。
 *
 * <ol>
 *   <li>必须显式指定 profile —— 无 profile 时 {@code application.yml} 的
 *       {@code sql.init.mode: never} 会生效，但同样意味着 local 的建库/灌种子逻辑可能被误用；
 *       更重要的是「没有 profile」通常等价于「生产漏设环境变量」，必须中止；</li>
 *   <li>导出/导入临时目录必须可写 —— {@code textbook.export.tmp-dir} 默认是相对路径
 *       （{@code ./data/export}），systemd 未设 WorkingDirectory 时会落到 {@code /} 不可写；</li>
 *   <li>微信密钥未配置时给出显式告警 —— 否则订阅消息会全量静默降级为 unauthorized。</li>
 * </ol>
 *
 * <p>JWT 密钥强度由 {@link com.tian.textbook.auth.JwtService} 构造期校验（同属启动自检）。</p>
 */
@Slf4j
@Component
public class StartupSelfCheck implements InitializingBean {

    private final Environment environment;
    private final TextbookProperties properties;

    public StartupSelfCheck(Environment environment, TextbookProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        assertProfilePresent();
        assertLocalProfileUsesLocalDb();
        assertTmpDirWritable();
        configureTrustedProxies();
        warnIfWeixinNotConfigured();
    }

    /**
     * local profile 只允许连本机数据库（第二道硬闸）。
     *
     * <p>{@link #assertProfilePresent()} 只能保证「profile 非空」，拦不住运维把
     * {@code SPRING_PROFILES_ACTIVE} 误写成 {@code local}——那会让 {@code spring.sql.init}
     * 向生产库执行 schema.sql + data-permission.sql + data-seed.sql，写入 18 个口令公开的
     * 测试账号（含超管 900001/Admin@123）。这里再加一道：local + 非环回主机 → 拒绝启动。</p>
     */
    private void assertLocalProfileUsesLocalDb() {
        boolean local = false;
        for (String profile : environment.getActiveProfiles()) {
            if ("local".equals(profile)) {
                local = true;
                break;
            }
        }
        if (!local) {
            return;
        }
        String url = environment.getProperty("spring.datasource.url", "");
        if (url == null || !url.startsWith("jdbc:mysql")) {
            return;
        }
        String host = hostOf(url);
        if (!"127.0.0.1".equals(host) && !"localhost".equalsIgnoreCase(host) && !"::1".equals(host)) {
            throw new IllegalStateException(
                    "local profile 只能连接本机数据库（当前 DB_URL 主机 = " + host + "）。"
                            + "local profile 会执行建库与种子脚本，连到生产库会写入口令公开的测试账号；"
                            + "生产请使用 SPRING_PROFILES_ACTIVE=trial|school。");
        }
    }

    /** 从 JDBC URL 取主机名（{@code jdbc:mysql://host:port/db}）。 */
    private static String hostOf(String url) {
        int start = url.indexOf("//");
        if (start < 0) {
            return "";
        }
        String rest = url.substring(start + 2);
        int end = rest.length();
        for (char delimiter : new char[]{':', '/'}) {
            int index = rest.indexOf(delimiter);
            if (index >= 0 && index < end) {
                end = index;
            }
        }
        return rest.substring(0, end);
    }

    /** 注入可信代理白名单（IpUtils 是静态工具，无 Spring 上下文）。 */
    private void configureTrustedProxies() {
        String trusted = properties.getSecurity().getLogin().getTrustedProxies();
        IpUtils.configureTrustedProxies(trusted);
        if (trusted == null || trusted.isBlank()) {
            log.info("未配置可信反向代理（textbook.security.login.trusted-proxies）："
                    + "X-Forwarded-For 一律不采信，客户端 IP 取 remoteAddr");
        } else {
            log.info("可信反向代理已生效: {}", trusted);
        }
    }

    /** 未显式指定 profile → 启动中止（历史上默认 local 会在生产执行 data-seed.sql 写入已知口令账号）。 */
    private void assertProfilePresent() {
        String[] active = environment.getActiveProfiles();
        if (active.length == 0) {
            throw new IllegalStateException(
                    "未指定 SPRING_PROFILES_ACTIVE：请显式选择运行环境（local / trial / school / test）。"
                            + "拒绝以无 profile 状态启动，避免生产误用 local 的建库与种子数据逻辑。");
        }
        log.info("启动自检通过: 运行环境 = {}", String.join(",", active));
    }

    /** 临时目录必须可创建且可写（导出落盘与导入上传共用该目录）。 */
    private void assertTmpDirWritable() {
        String configured = properties.getExport().getTmpDir();
        Path dir = Path.of(configured == null || configured.isBlank() ? "./data/export" : configured)
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
            if (!Files.isWritable(dir)) {
                throw new IllegalStateException("导出临时目录不可写: " + dir);
            }
        } catch (IOException | SecurityException e) {
            throw new IllegalStateException(
                    "导出临时目录不可用: " + dir + "（请检查 textbook.export.tmp-dir 与进程工作目录权限）", e);
        }
        log.info("启动自检通过: 导出临时目录 = {}", dir);
    }

    /** 微信小程序密钥缺失时明确告警：订阅消息将全量降级为 unauthorized，且不会自动恢复。 */
    private void warnIfWeixinNotConfigured() {
        TextbookProperties.Miniapp miniapp = properties.getWeixin().getMiniapp();
        boolean appidMissing = miniapp.getAppid() == null || miniapp.getAppid().isBlank();
        boolean secretMissing = miniapp.getSecret() == null || miniapp.getSecret().isBlank();
        boolean templateMissing = miniapp.getSubscribeTemplateId() == null
                || miniapp.getSubscribeTemplateId().isBlank();
        if (appidMissing || secretMissing || templateMissing) {
            log.warn("微信小程序未完整配置（appid缺失={}, secret缺失={}, 模板id缺失={}）："
                            + "订阅消息通道将整体降级，notice_record 会如实记为 unauthorized（弹窗通道不受影响）",
                    appidMissing, secretMissing, templateMissing);
        }
    }
}
