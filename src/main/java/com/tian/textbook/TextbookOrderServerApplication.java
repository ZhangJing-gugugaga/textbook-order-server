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
        SpringApplication.run(TextbookOrderServerApplication.class, args);
    }
}
