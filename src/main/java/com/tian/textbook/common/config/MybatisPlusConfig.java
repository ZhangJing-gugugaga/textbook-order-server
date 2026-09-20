package com.tian.textbook.common.config;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tian.textbook.common.datascope.CollegeScopeHandler;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * MyBatis-Plus 配置：分页插件 + 数据权限插件（W10）+ JSON 列类型处理器。
 *
 * <p>注意顺序：先分页后数据权限（MP 官方组合示例）。逻辑删除（deleted）由各服务显式处理
 * （deleted=0 过滤 + 删除写时间戳，W9），不开启 MP 逻辑删除。</p>
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 分页插件 + 数据权限插件（W10）。
     *
     * <p>顺序：先分页后数据权限（MP 官方组合示例）。逻辑删除（deleted）由各服务显式处理
     * （deleted=0 过滤 + 删除写时间戳，W9），不开启 MP 逻辑删除。</p>
     *
     * <p>@Lazy：数据权限处理器经 UserScopeService 依赖 Mapper，而 sqlSessionFactory 的
     * 构建又需要本拦截器——懒加载打破这轮循环（处理器在首次查询时才真正初始化）。</p>
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(@Lazy CollegeScopeHandler collegeScopeHandler) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        interceptor.addInnerInterceptor(new DataPermissionInterceptor(collegeScopeHandler));
        return interceptor;
    }

    /**
     * JSON 列（field_check_result / submit_snapshot / payload_json / detail_json …）读写
     * 统一走 Spring 的 ObjectMapper。
     *
     * <p>MP 自带默认 ObjectMapper 未注册 jackson-datatype-jsr310，写入含 {@code LocalDateTime}
     * 的 Map（如窗口变更审计的 before/after 快照）会抛 InvalidDefinitionException，
     * 被 AuditService 的兜底 catch 吞掉 → 审计静默丢失。此处注入容器内的 ObjectMapper
     * （已注册 JavaTimeModule，且与 HTTP 序列化同一份配置，保证读写一致）。</p>
     */
    @Bean
    public SmartInitializingSingleton jacksonTypeHandlerObjectMapper(ObjectMapper objectMapper) {
        return () -> JacksonTypeHandler.setObjectMapper(objectMapper);
    }
}
