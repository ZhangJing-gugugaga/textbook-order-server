package com.tian.textbook.common.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.tian.textbook.common.datascope.CollegeScopeHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * MyBatis-Plus 配置：分页插件 + 数据权限插件（W10）。
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
}
