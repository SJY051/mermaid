package com.mermaid.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class LoggingConfigTest {

    @Test
    @DisplayName("the log pattern renders a request ID and remains valid when MDC is empty")
    void configuredPatternCarriesRequestIdAndToleratesMissingMdc() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();
        String pattern = properties.getProperty("logging.pattern.level");
        assertThat(pattern).contains("%X{requestId:-}");

        var context = new LoggerContext();
        var layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern(pattern);
        layout.start();

        var withRequestId = new LoggingEvent();
        withRequestId.setLevel(Level.INFO);
        withRequestId.setMDCPropertyMap(Map.of("requestId", "request-123"));
        assertThat(layout.doLayout(withRequestId)).contains("requestId=request-123");

        var withoutRequestId = new LoggingEvent();
        withoutRequestId.setLevel(Level.INFO);
        withoutRequestId.setMDCPropertyMap(Map.of());
        assertThat(layout.doLayout(withoutRequestId)).contains("requestId=");
    }
}
