package com.tian.textbook.importexport.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * 上传容量配置（application*.yml 不在本模块改动范围时的程序化等价物）。
 *
 * <p>Spring Boot 默认单文件上限 1MB，会导致「≤ import.max_file_mb（默认 10MB）」的业务规则
 * 在 1~10MB 文件上被容器层抢先拒绝（MaxUploadSizeExceeded → FILE_TOO_LARGE）。
 * 这里把容器上限放到业务白名单最大值（100MB），实际大小仍由
 * {@link com.tian.textbook.importexport.support.ImportUploadValidator} 按
 * system_config.import.max_file_mb 校验。</p>
 */
@Configuration
public class UploadConfig {

    /** 与 ConfigService.KEY_WHITELIST 中 import.max_file_mb 的值域上限一致 */
    private static final long MAX_UPLOAD_MB = 100L;

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofMegabytes(MAX_UPLOAD_MB));
        factory.setMaxRequestSize(DataSize.ofMegabytes(MAX_UPLOAD_MB));
        return factory.createMultipartConfig();
    }
}
