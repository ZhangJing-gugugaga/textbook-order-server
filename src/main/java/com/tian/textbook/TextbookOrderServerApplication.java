package com.tian.textbook;

import com.tian.textbook.common.config.TextbookProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 教材征订系统服务端（textbook-order-server）。
 *
 * <p>单体分层服务，按模块分包（SPEC §2）：auth / system / semester / textbook / order /
 * approval / importexport / notify / supplier / common。</p>
 *
 * <p>定时任务（窗口引擎扫描、通知重发、导出文件清理）随 Boot 单机运行，
 * 时区固定 Asia/Shanghai（SPEC §6）。</p>
 */
@EnableScheduling
@EnableConfigurationProperties(TextbookProperties.class)
@SpringBootApplication
public class TextbookOrderServerApplication {

    public static void main(String[] args) {
        assertProfileSpecified(args);
        SpringApplication.run(TextbookOrderServerApplication.class, args);
    }

    /**
     * profile 必填的前置断言（与 {@link com.tian.textbook.common.config.StartupSelfCheck} 同一规则）。
     *
     * <p>放在 {@code run()} 之前是为了让错误信息指向真正的原因：容器启动后 JwtService
     * 会先于 StartupSelfCheck 构造，未设 profile 时会先抛「JWT_SECRET 未配置」，
     * 把运维引向错误的排查方向。</p>
     *
     * <p>只作用于命令行启动路径；测试（{@code @ActiveProfiles("test")}）不经 main，
     * 由 StartupSelfCheck 覆盖同一断言。</p>
     */
    private static void assertProfileSpecified(String[] args) {
        String prefix = "--spring.profiles.active=";
        for (String arg : args) {
            if (arg.startsWith(prefix) && !arg.substring(prefix.length()).isBlank()) {
                return;
            }
        }
        if (isNotBlank(System.getenv("SPRING_PROFILES_ACTIVE"))
                || isNotBlank(System.getProperty("spring.profiles.active"))) {
            return;
        }
        throw new IllegalStateException(
                "未指定 SPRING_PROFILES_ACTIVE：请显式选择运行环境（local / trial / school）。"
                        + "拒绝以无 profile 状态启动，避免生产误用 local 的建库与种子数据逻辑。");
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
