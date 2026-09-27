package com.claimflow.validation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Scan com.claimflow so shared beans in the common module (correlation filter, exception handler) are picked up.
@SpringBootApplication(scanBasePackages = "com.claimflow")
public class ValidationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ValidationServiceApplication.class, args);
    }
}
