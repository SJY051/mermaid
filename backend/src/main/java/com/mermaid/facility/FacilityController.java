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

    /**
     * e.g. {@code ?lat=37.56&lng=126.97&radius_m=500&open_now=true&type=pharmacy}
     *
     * <p>{@code open_now=true} returns only facilities we know to be open. A facility whose
     * timetable we could not read is excluded rather than guessed at — see spec §2-13. {@code limit}
     * bounds the nearest candidates before hospital detail fan-out, keeping a dense map load within
     * the public API quota.
     */
    @GetMapping
    public List<Facility> nearby(
            @RequestParam @Min(-90) @Max(90) double lat,
            @RequestParam @Min(-180) @Max(180) double lng,
            @RequestParam(name = "radius_m", defaultValue = "1000") @Min(100) @Max(10_000) int radiusM,
            @RequestParam(name = "open_now", defaultValue = "false") boolean openNow,
            @RequestParam(defaultValue = "pharmacy") FacilityType type,
            @RequestParam(defaultValue = "50") @Min(1) @Max(FacilityService.MAX_FACILITY_RESULTS) int limit) {
        return facilityService.findNearby(lat, lng, radiusM, openNow, type, limit);
    }

    /**
     * TODO(team): single-facility detail (UI-03).
     *
     * <p>{@code id} contains an {@code hpid} for a pharmacy ({@code getParmacyBassInfoInqire}) or a
     * URL-safe encoded {@code ykiho} for a hospital ({@code MadmDtlInfoService2.8/getDtlInfo2.8}).
     *
     * <p>Until it is written this answers <b>501 NOT_IMPLEMENTED</b>, which is the honest status: our
     * fault, but nothing is broken.
     */
    @GetMapping("/{id}")
    public Facility detail(@PathVariable String id) {
        throw new UnsupportedOperationException("Not implemented — see FacilityController#detail");
    }
}
