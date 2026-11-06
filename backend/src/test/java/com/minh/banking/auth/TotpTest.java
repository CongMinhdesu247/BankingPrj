package com.minh.banking.auth;
import com.minh.banking.auth.service.Totp;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class TotpTest {
    @Test void matchesRfc6238Sha1Vectors() {
        String secret = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
        long[] times = {59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
        String[] expected = {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"};
        for (int i=0; i<times.length; i++) assertThat(Totp.code(secret, times[i]/30, 8)).isEqualTo(expected[i]);
    }
    @Test void encryptsWithUserBindingAndPreventsOtpReplay() {
        var totp = new Totp(Base64.getEncoder().encodeToString(new byte[32]));
        var id = UUID.randomUUID();
        var secret = totp.newSecret();
        var encrypted = totp.encrypt(id, secret);
        assertThat(encrypted).doesNotContain(secret);
        assertThat(totp.decrypt(id, encrypted)).isEqualTo(secret);
        assertThatThrownBy(() -> totp.decrypt(UUID.randomUUID(), encrypted)).isInstanceOf(IllegalStateException.class);
        String code = Totp.code(secret, 100, 6);
        assertThat(totp.match(secret, code, 3000, -1)).isEqualTo(100);
        assertThat(totp.match(secret, code, 3000, 100)).isEqualTo(-1);
        assertThat(totp.match(secret, "invalid", 3000, -1)).isEqualTo(-1);
    }
}
