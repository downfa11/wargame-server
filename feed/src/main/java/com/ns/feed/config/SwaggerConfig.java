package com.ns.feed.config;



import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public GroupedOpenApi OpenApiCommunity() {
        String[] paths = { "/**" };
        return GroupedOpenApi.builder().
                group("community")
                .addOpenApiCustomizer(openApi -> openApi.info(new Info().title("Feed v1 API")))
                .pathsToMatch(paths)
                .build();
    }

}