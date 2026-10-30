package com.ventrax.installation.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.cucumber.core.cli.Main;

import org.junit.jupiter.api.Test;

class BddAcceptanceTest {
    @Test
    void executableGherkinAcceptanceScenarios() {
        byte result =
                Main.run(
                        new String[] {
                            "--glue",
                            "com.ventrax.installation.bdd",
                            "--plugin",
                            "pretty",
                            "classpath:features"
                        },
                        getClass().getClassLoader());
        assertEquals(0, result, "All Gherkin acceptance scenarios must execute successfully");
    }
}
