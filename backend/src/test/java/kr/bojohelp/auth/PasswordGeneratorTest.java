package kr.bojohelp.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class PasswordGeneratorTest {

    @RepeatedTest(50)
    void temporaryPasswordMeetsPolicyAndAvoidsAmbiguousCharacters() {
        String p = PasswordGenerator.temporary();
        assertThat(p).hasSize(12).doesNotContain("0", "O", "1", "l", "I");
        assertThat(PasswordPolicy.isAcceptable(p, "a@b.c")).isTrue();
    }

    @Test
    void valuesDiffer() {
        Set<String> s = new HashSet<>();
        for (int i = 0; i < 100; i++) s.add(PasswordGenerator.temporary());
        assertThat(s).hasSize(100);
    }

    @Test
    void masksIpForEmails() {
        assertThat(ClientIp.mask("203.0.113.77")).isEqualTo("203.0.113.***");
        assertThat(ClientIp.mask("2001:db8:85a3::1")).isEqualTo("2001:db8:***");
    }
}
