package com.tian.textbook.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3（W12：唯一契约源，M1 末随契约冻结）。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI textbookOpenApi() {
        return new OpenAPI().info(new Info()
                .title("教材征订系统服务端 API")
                .version("v1")
                .description("textbook-order-server · MVP · 契约基线见 SPEC §11"));
    }
}
