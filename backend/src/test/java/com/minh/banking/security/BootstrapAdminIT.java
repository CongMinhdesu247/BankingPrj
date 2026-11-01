package com.minh.banking.security;

import com.minh.banking.transaction.support.TransferIntegrationTestSupport;
import com.minh.banking.user.service.BootstrapAdminService;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("dev")
class BootstrapAdminIT extends TransferIntegrationTestSupport {
    @Autowired BootstrapAdminService bootstrap;

    @Test
    void bootstrapCreatesOneAdminOnlyWhenUserTableIsEmptyAndNeverResetsExistingPassword() throws Exception {
        assertThat(bootstrap.seed("seed.admin", testUsers.password())).isTrue();
        String oldHash = jdbc.queryForObject("select password_hash from users where username='seed.admin'", String.class);
        assertThat(bootstrap.seed("other.admin", testUsers.password())).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select password_hash from users where username='seed.admin'", String.class)).isEqualTo(oldHash);
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                "{\"username\":\"seed.admin\",\"password\":\"" + testUsers.password() + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("user").get("roles").get(0).asText()).isEqualTo("ADMIN");
    }

    @Test
    void bootstrapRejectsWeakPasswordWithoutWritingUser() {
        assertThatThrownBy(() -> bootstrap.seed("seed.admin", "short")).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isZero();
    }
}
