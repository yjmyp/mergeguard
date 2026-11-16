package dev.yujian.mergeguard.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpConfig {

    @Bean
    public RestClient githubRestClient(MergeGuardProperties properties) {
        JdkClientHttpRequestFactory factory = factory(properties.getGithub().getTimeoutSeconds());
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.getGithub().getApiUrl())
                .requestFactory(factory)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader("User-Agent", "MergeGuard/0.1");
        String token = properties.getGithub().getToken();
        if (token != null && !token.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }
        return builder.build();
    }

    @Bean
    public RestClient llmRestClient(MergeGuardProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.getLlm().getBaseUrl())
                .requestFactory(factory(properties.getLlm().getTimeoutSeconds()))
                .defaultHeader("Authorization", "Bearer " + properties.getLlm().getApiKey())
                .build();
    }

    private JdkClientHttpRequestFactory factory(int readTimeoutSeconds) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        return factory;
    }
}
