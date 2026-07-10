package com.mermaid.config;

import com.mermaid.facility.domain.FacilityType;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Lets {@code ?type=pharmacy} bind to {@link FacilityType}.
 *
 * <p>Without this, Spring falls back to {@code Enum.valueOf} and only accepts {@code PHARMACY} in
 * screaming caps. Jackson's {@code @JsonCreator} does not apply to query parameters — it only runs
 * for request bodies.
 */
@Component
public class FacilityTypeConverter implements Converter<String, FacilityType> {

    @Override
    public FacilityType convert(String source) {
        return FacilityType.from(source);
    }
}
