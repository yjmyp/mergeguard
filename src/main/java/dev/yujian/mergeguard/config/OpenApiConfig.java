package dev.yujian.mergeguard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI mergeGuardOpenApi() {
        return new OpenAPI().info(new Info()
                .title("MergeGuard API")
                .description("Pull request merge-risk review service")
                .version("0.1.0"));
    }
}
