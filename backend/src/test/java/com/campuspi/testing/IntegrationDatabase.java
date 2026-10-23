package com.campuspi.testing;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Explicit opt-in only; refuses arbitrary development or production databases. */
public final class IntegrationDatabase {
    private IntegrationDatabase(){}
    public static final Path ROOT=Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();
    public static final boolean CI="true".equals(System.getenv("GITHUB_ACTIONS"));
    public static final boolean FRESH="true".equals(System.getenv("CAMPUS_TEST_FRESH_DATABASE"));
    static JsonNode config(){
        try{
            if(CI){var node=new ObjectMapper().createObjectNode();node.put("host","127.0.0.1");node.put("port",33320);node.put("database","campus_pi_ci");node.put("user",Objects.requireNonNull(System.getenv("CAMPUS_TEST_DB_USER")));node.put("password",Objects.requireNonNull(System.getenv("CAMPUS_TEST_DB_PASSWORD")));node.put("idempotency_secret","ci-isolated-test-only-"+"x".repeat(48));return node;}
            if(FRESH){JsonNode value=new ObjectMapper().readTree(ROOT.resolve(".local/remaining/fresh-db.json").toFile());if(!value.path("host").asText().equals("127.0.0.1")||value.path("port").asInt()!=33321||!value.path("database").asText().equals("campus_pi_ci_local"))throw new IllegalStateException();return value;}
            JsonNode value=new ObjectMapper().readTree(ROOT.resolve(".local/auth-dev/private.json").toFile());
            if(!value.path("host").asText().equals("127.0.0.1")||value.path("port").asInt()!=33320||!value.path("database").asText().equals("campus_pi_auth_local"))throw new IllegalStateException();return value;
        }catch(Exception ex){throw new IllegalStateException("Verified isolated test database config unavailable");}
    }
    public static void configure(DynamicPropertyRegistry registry){var c=config();registry.add("spring.datasource.url",()->"jdbc:mysql://127.0.0.1:"+c.path("port").asInt()+"/"+c.path("database").asText()+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&sslMode=DISABLED");registry.add("spring.datasource.username",()->c.path("user").asText());registry.add("spring.datasource.password",()->c.path("password").asText());registry.add("campus.auth.idempotency-secret",()->c.path("idempotency_secret").asText());}
    public static void verify(JdbcTemplate db){var row=db.queryForMap("SELECT @@datadir AS directory,@@port AS port,DATABASE() AS db");
        if(CI){if(!row.get("db").equals("campus_pi_ci")||((Number)row.get("port")).intValue()!=3306||!row.get("directory").toString().startsWith("/var/lib/mysql"))throw new IllegalStateException("Refusing non-CI database");}
        else if(FRESH){if(!row.get("db").equals("campus_pi_ci_local")||((Number)row.get("port")).intValue()!=33321||!Path.of(row.get("directory").toString()).normalize().equals(ROOT.resolve(".local/remaining/fresh-mysql-data").normalize()))throw new IllegalStateException("Refusing non-fresh local test database");}
        else if(!row.get("db").equals("campus_pi_auth_local")||((Number)row.get("port")).intValue()!=33320||!Path.of(row.get("directory").toString()).normalize().equals(ROOT.resolve(".local/auth-dev/mysql-data").normalize()))throw new IllegalStateException("Refusing another local database");
    }
}
