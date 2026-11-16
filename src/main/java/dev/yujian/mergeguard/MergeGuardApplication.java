package dev.yujian.mergeguard;

import dev.yujian.mergeguard.config.MergeGuardProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(MergeGuardProperties.class)
public class MergeGuardApplication {
    public static void main(String[] args) {
        SpringApplication.run(MergeGuardApplication.class, args);
    }
}
