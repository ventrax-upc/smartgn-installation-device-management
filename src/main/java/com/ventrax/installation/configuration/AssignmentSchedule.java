package com.ventrax.installation.configuration;

import com.ventrax.installation.installations.application.InstallationOperations;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "smartgn.assignment.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AssignmentSchedule {
    private final InstallationOperations operations;

    public AssignmentSchedule(InstallationOperations operations) {
        this.operations = operations;
    }

    @Scheduled(fixedDelayString = "${smartgn.assignment.delay-ms:1000}")
    public void run() {
        try {
            operations.assignAvailable();
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn(
                            "Installer assignment will retry: {}",
                            failure.getClass().getSimpleName());
        }
    }
}
