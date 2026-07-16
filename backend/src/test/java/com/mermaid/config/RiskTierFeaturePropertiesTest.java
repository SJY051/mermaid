package com.mermaid.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class RiskTierFeaturePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void everyRiskTierCapabilityIsDisabledWhenNoActivationPropertyIsPresent() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            RiskTierFeatureProperties properties =
                    context.getBean(RiskTierFeatureProperties.class);
            assertThat(properties.generalExplanationEnabled()).isFalse();
            assertThat(properties.ambiguousPlanningEnabled()).isFalse();
            assertThat(properties.t5PolicyEnabled()).isFalse();
        });
    }

    @Test
    void t5CanActivateWithoutAmbiguousPlanningOrGeneralExplanation() {
        contextRunner
                .withPropertyValues("mermaid.chat.risk-tier.t5-policy-enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RiskTierFeatureProperties properties =
                            context.getBean(RiskTierFeatureProperties.class);
                    assertThat(properties.generalExplanationEnabled()).isFalse();
                    assertThat(properties.ambiguousPlanningEnabled()).isFalse();
                    assertThat(properties.t5PolicyEnabled()).isTrue();
                });
    }

    @Test
    void generalExplanationCannotActivateWithoutAmbiguousPlanning() {
        assertThatThrownBy(() -> new RiskTierFeatureProperties(true, false, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambiguous planning");
    }

    @Test
    void generalExplanationAndAmbiguousPlanningCanActivateWithoutT5() {
        assertThat(new RiskTierFeatureProperties(true, true, false))
                .satisfies(properties -> {
                    assertThat(properties.generalExplanationEnabled()).isTrue();
                    assertThat(properties.ambiguousPlanningEnabled()).isTrue();
                    assertThat(properties.t5PolicyEnabled()).isFalse();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RiskTierFeatureProperties.class)
    static class TestConfiguration {}
}
