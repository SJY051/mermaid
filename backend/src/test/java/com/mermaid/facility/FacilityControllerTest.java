package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.mermaid.common.ApiException;
import com.mermaid.common.ErrorCode;
import com.mermaid.facility.domain.FacilityOperationPreference;
import com.mermaid.facility.domain.FacilityType;
import org.junit.jupiter.api.Test;

class FacilityControllerTest {

    private final FacilityService service = mock(FacilityService.class);
    private final FacilityController controller = new FacilityController(service);

    @Test
    void explicitOperationPreferenceReachesTheServiceUnchanged() {
        controller.nearby(
                37.5,
                127.0,
                1000,
                null,
                FacilityOperationPreference.OPEN_OR_UNKNOWN,
                FacilityType.PHARMACY,
                10);

        verify(service).findNearby(
                37.5,
                127.0,
                1000,
                FacilityOperationPreference.OPEN_OR_UNKNOWN,
                FacilityType.PHARMACY,
                10);
    }

    @Test
    void legacyOpenNowMapsToConfirmedOpenOnly() {
        controller.nearby(
                37.5, 127.0, 1000, true, null, FacilityType.HOSPITAL, 10);

        verify(service).findNearby(
                37.5,
                127.0,
                1000,
                FacilityOperationPreference.CONFIRMED_OPEN_ONLY,
                FacilityType.HOSPITAL,
                10);
    }

    @Test
    void conflictingLegacyAndNewParametersAreRejectedAsAClientError() {
        assertThatThrownBy(() -> controller.nearby(
                        37.5,
                        127.0,
                        1000,
                        true,
                        FacilityOperationPreference.OPEN_OR_UNKNOWN,
                        FacilityType.PHARMACY,
                        10))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }
}
