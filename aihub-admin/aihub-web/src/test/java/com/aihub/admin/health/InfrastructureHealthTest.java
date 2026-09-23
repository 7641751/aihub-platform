package com.aihub.admin.health;

import com.aihub.admin.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class InfrastructureHealthTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthzReportsAllThreeInfrastructureComponents() {
        ResponseEntity<String> response = restTemplate.getForEntity("/healthz", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("\"status\":\"UP\"");
        assertThat(body).contains("\"db\"");
        assertThat(body).contains("\"redis\"");
        assertThat(body).contains("\"rabbit\"");
    }
}
