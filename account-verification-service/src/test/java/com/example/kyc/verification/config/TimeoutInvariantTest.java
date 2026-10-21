package com.example.kyc.verification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** Invariant: overall deadline < connect + read in every profile, or the deadline path is unreachable. */
class TimeoutInvariantTest {

    private static Properties yaml(String name) {
        YamlPropertiesFactoryBean f = new YamlPropertiesFactoryBean();
        f.setResources(new ClassPathResource(name));
        return f.getObject();
    }

    private static void assertInvariant(Properties p) {
        Duration connect = Duration.parse(p.getProperty("kyc.identity-provider.connect-timeout"));
        Duration read = Duration.parse(p.getProperty("kyc.identity-provider.read-timeout"));
        Duration deadline = Duration.parse(p.getProperty("kyc.identity-provider.overall-deadline"));
        assertThat(deadline).isLessThan(connect.plus(read));
    }

    @Test
    void composeDefaultsSatisfyTheInvariant() {
        assertInvariant(yaml("application.yml"));
    }

    @Test
    void testProfileSatisfiesTheInvariant() {
        Properties merged = yaml("application.yml");
        merged.putAll(yaml("application-test.yml")); // test profile overrides win
        assertInvariant(merged);
    }

    @Test
    void startupRejectsAConfigurationWhereTheDeadlineIsUnreachable() {
        assertThatThrownBy(() -> new KycProperties.IdentityProvider("http://x", Duration.ofMillis(300),
                Duration.ofMillis(800), Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new KycProperties.IdentityProvider("http://x", Duration.ofMillis(300), Duration.ofMillis(1500),
                Duration.ofSeconds(1))).isNotNull();
    }
}
