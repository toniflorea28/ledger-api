package com.toniflorea.ledger.bdd;

import com.toniflorea.ledger.TestcontainersConfiguration;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@CucumberContextConfiguration
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTracing
public class CucumberSpringConfiguration {
}
