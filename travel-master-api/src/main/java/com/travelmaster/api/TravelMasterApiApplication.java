package com.travelmaster.api;

import com.travelmaster.api.config.DeepSeekProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(DeepSeekProperties.class)
public class TravelMasterApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(TravelMasterApiApplication.class, args);
    }
}
