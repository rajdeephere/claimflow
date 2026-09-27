package com.claimflow.claim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Scan com.claimflow so shared beans in the common module (correlation filter, exception handler) are picked up.
@SpringBootApplication(scanBasePackages = "com.claimflow")
public class ClaimServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClaimServiceApplication.class, args);
    }
}
