package com.mermaid.config;

import com.mermaid.facility.domain.FacilityOperationPreference;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/** Binds the lowercase {@code operation_preference} query contract to its domain enum. */
@Component
public class FacilityOperationPreferenceConverter
        implements Converter<String, FacilityOperationPreference> {

    @Override
    public FacilityOperationPreference convert(String source) {
        return FacilityOperationPreference.from(source);
    }
}
