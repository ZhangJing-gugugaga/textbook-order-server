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
    private final Cors cors = new Cors();
    private final Import importConfig = new Import();
    private final Export export = new Export();
    private final Async async = new Async();
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
        /**
         * 可信反向代理地址（逗号分隔 IP）。只有来自这些地址的 X-Forwarded-For 才被采信，
         * 否则一律以 remoteAddr 为准（防 XFF 伪造绕过限频 / 伪造审计 IP）。
         * 生产应为 Nginx 所在主机地址（同机部署填 127.0.0.1,::1）。
         */
        private String trustedProxies = "";
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
    public static class Cors {
        /**
         * 跨域来源白名单（逗号分隔的完整 origin，如 {@code https://moonzj.com}）。
         * 默认空 = 不返回任何 CORS 头：Web 端经 Nginx 同域反代，小程序端请求不受 CORS 约束。
         * 严禁配置为 {@code *}（配合 allowCredentials 会让任意站点带凭证读取响应）。
         */
        private String allowedOrigins = "";
    }

    @Data
    public static class Import {
        /** 上传上限 MB（与 system_config.import.max_file_mb 对应） */
        private int maxFileMb = 10;
        /**
         * 局部名单防护（B13）：学生名单导入会把 {@code school_class.student_count} 按
         * 「文件内该班去重人数」重算，而班级人数是教师填报数量的硬上限——文件里只放了几行
         * 局部名单就会把整班上限压小（线上实测 50 → 2，该班教师填报随即被卡死）。
         *
         * <p>下调幅度同时满足「比例 &gt; 本值(%)」与「绝对人数 &ge;
         * {@link #classSizeShrinkConfirmMinDrop}」时视为疑似局部名单：导入请求必须带
         * {@code confirmClassSizeShrink=true}，否则 409 并回显逐班 diff
         * （{@code POST /api/admin/user/import/preview} 可先预览）。</p>
         */
        private int classSizeShrinkConfirmPct = 20;
        /**
         * 局部名单防护的绝对人数下限：小班（如 3 人班降到 2 人）比例天然很大，
         * 只用比例判定会把正常的小幅调整也拦下，故同时要求下调人数不少于本值。
         */
        private int classSizeShrinkConfirmMinDrop = 5;
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
    public static class Async {
        /**
         * 启动时是否回收上次进程遗留的 running/queued 任务（默认开）。
         *
         * <p>单实例部署保持默认即可。多实例（滚动发布）应置 false：新实例启动时
         * 会把旧实例正在执行的任务误判为遗留。彻底方案是 owner_instance + 实例心跳。</p>
         */
        private boolean recoverOnStartup = true;
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
