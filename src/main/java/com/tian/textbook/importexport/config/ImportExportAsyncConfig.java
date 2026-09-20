package com.tian.textbook.importexport.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 导入导出异步开关（SPEC §10：@Async(importExecutor/exportExecutor) 需要 @EnableAsync）。
 *
 * <p>仅本模块使用 @Async（导入解析 / 导出生成）；线程池定义见
 * {@link com.tian.textbook.common.config.AsyncConfig}。</p>
 */
@Configuration
@EnableAsync
public class ImportExportAsyncConfig {
}
