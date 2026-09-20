package com.tian.textbook.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 业务配置（SPEC §13 application-{env}.yml 主要键，类型安全注入）。
 */
@Data
@ConfigurationProperties(prefix = "textbook")
public class TextbookProperties {

    private final Security security = new Security();
    private final Jwt jwt = new Jwt();
    private final Import importConfig = new Import();
    private final Export export = new Export();
    private final Weixin weixin = new Weixin();

    @Data
    public static class Security {
        private final Login login = new Login();
    }

    @Data
    public static class Login {
        /** 登录失败 N 次锁定（W20） */
        private int maxFail = 5;
        /** 锁定时长（分钟） */
        private int lockMinutes = 15;
        /** 同 IP/账号维度限频（次/分钟，Caffeine 加速） */
        private int ratePerMinute = 10;
    }

    @Data
    public static class Jwt {
        /** access 15 分钟 */
        private int accessMinutes = 15;
        /** refresh 7 天 */
        private int refreshDays = 7;
        /** 签名密钥（环境变量 JWT_SECRET，≥32 字节随机） */
        private String secret;
    }

    @Data
    public static class Import {
        /** 上传上限 MB（与 system_config.import.max_file_mb 对应） */
        private int maxFileMb = 10;
        private final Pool pool = new Pool();
    }

    @Data
    public static class Pool {
        /** 专用线程池：核心 2 / 最大 4 / 队列 50 */
        private int coreSize = 2;
        private int maxSize = 4;
        private int queueCapacity = 50;
    }

    @Data
    public static class Export {
        /** 导出临时目录 */
        private String tmpDir = "./data/export";
        /** 预估行数 > 阈值走异步任务（Q16） */
        private int syncRowThreshold = 5000;
        /** 一次性下载 token 有效期（分钟） */
        private int downloadTokenMinutes = 10;
        /** 文件保留小时数 */
        private int retentionHours = 24;
    }

    @Data
    public static class Weixin {
        private final Miniapp miniapp = new Miniapp();
    }

    @Data
    public static class Miniapp {
        private String appid = "";
        private String secret = "";
        /** 订阅消息模板 id（环境变量 WX_SUBSCRIBE_TEMPLATE_ID；未申请时重发记 unauthorized，W5/R10） */
        private String subscribeTemplateId = "";
    }
}
