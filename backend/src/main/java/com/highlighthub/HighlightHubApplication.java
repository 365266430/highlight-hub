package com.highlighthub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HighlightHubApplication {
    public static void main(String[] args) {
        SpringApplication.run(HighlightHubApplication.class, args);
    }
}
