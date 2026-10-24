package com.campuspi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.env.ConfigurableEnvironment;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"campus.notifications.worker-enabled=false", "campus.admin-batches.worker-enabled=false", "campus.wechat.enabled=false"})
class BootstrapTest {
    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ConfigurableEnvironment environment;

    @Test
    void standardClasspathApplicationYamlLoadsWithoutExtraLocationArguments() {
        boolean loaded = false;
        for (var source : environment.getPropertySources()) {
            if (source.getName().contains("class path resource [application.yml]")
                    && source.getProperty("spring.datasource.url") != null) {
                loaded = true;
            }
        }
        assertThat(loaded).as("standard application.yml is loaded from the runtime classpath").isTrue();
    }

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
    void disabledWechatDoesNotRequireOrContactExternalServices() {
        ResponseEntity<JsonNode> config=rest.getForEntity("/api/v1/auth/wechat/config",JsonNode.class);
        assertThat(config.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(config.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(config.getBody().path("data").path("available").asBoolean()).isFalse();
        ResponseEntity<JsonNode> login=rest.postForEntity("/api/v1/auth/wechat/login",java.util.Map.of("code","synthetic_unused_code"),JsonNode.class);
        assertThat(login.getStatusCode().value()).isEqualTo(501);
        assertThat(login.getBody().path("code").asText()).isEqualTo("FEATURE_NOT_ENABLED");
        ResponseEntity<JsonNode> bind=rest.postForEntity("/api/v1/me/wechat",java.util.Map.of("code","synthetic_unused_code"),JsonNode.class);
        assertThat(bind.getStatusCode().value()).isEqualTo(401);
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
