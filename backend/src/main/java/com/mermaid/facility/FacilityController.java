package com.mermaid.facility;

import com.mermaid.facility.domain.Facility;
import com.mermaid.facility.domain.FacilityType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Nearby medical facilities (FR-02).
 *
 * <p>The frontend calls this directly, and the assistant also asks for it indirectly by filling the
 * {@code map} field of its response (spec §2-1).
 */
@RestController
@RequestMapping("/api/v1/facilities")
@RequiredArgsConstructor
@Validated
public class FacilityController {

    private final FacilityService facilityService;

    /** e.g. {@code ?lat=37.56&lng=126.97&radius=500&open_now=true&type=pharmacy} */
    @GetMapping
    public List<Facility> nearby(
            @RequestParam @Min(-90) @Max(90) double lat,
            @RequestParam @Min(-180) @Max(180) double lng,
            @RequestParam(defaultValue = "1000") @Min(1) @Max(20_000) int radius,
            @RequestParam(name = "open_now", defaultValue = "false") boolean openNow,
            @RequestParam(defaultValue = "pharmacy") FacilityType type) {
        return facilityService.findNearby(lat, lng, radius, openNow, type);
    }

    /**
     * TODO(team): single-facility detail (UI-03).
     *
     * <p>{@code id} is an {@code hpid} for a pharmacy ({@code getParmacyBassInfoInqire}) or a {@code
     * ykiho} for a hospital ({@code MadmDtlInfoService2.8/getDtlInfo}).
     */
    @GetMapping("/{id}")
    public Facility detail(@PathVariable String id) {
        throw new UnsupportedOperationException("Not implemented — see FacilityController#detail");
    }
}
