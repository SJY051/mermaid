package com.mermaid.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Time is injected, never read from a static.
 *
 * <p>{@code FacilityService} decides whether a pharmacy is open right now. A test that cannot pin
 * "now" to a Tuesday at 01:00 cannot test the night-pharmacy case at all.
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
