package com.campuspi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BootstrapTest {
    @Autowired
    private TestRestTemplate rest;

    @Test
    void frontendEntriesDoNotReuseAnOldAssetManifest() {
        for (String path : new String[] {"/", "/index.html", "/admin/login", "/admin/modules/analytics"}) {
            ResponseEntity<String> response = rest.getForEntity(path, String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(response.getHeaders().getLastModified()).isEqualTo(-1L);
            assertThat(response.getBody()).contains("/assets/");
        }
    }

    @Test
    void unknownRouteReturnsReal404AndSafeErrorEnvelope() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/v1/__baseline_unknown_route__", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(body.has("data")).isTrue();
        assertThat(body.path("data").isNull()).isTrue();
        assertThat(body.path("request_id").asText()).isNotBlank();
        assertThat(body.path("errors").isArray()).isTrue();
        assertThat(body.has("trace")).isFalse();
        assertThat(body.has("exception")).isFalse();
    }
}
