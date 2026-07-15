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
     * timetable we could not read is excluded rather than guessed at — see spec §2-13. For
     * both facility types, {@code open_now=true} inspects a distance-ranked, bounded set of at most
     * 100 candidates before returning the requested nearest open results. The cap keeps upstream
     * timetable fan-out within the public API quota.
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
     * A single facility by its namespaced id (UI-03, DEV-205), e.g. {@code facility:nmc:C1110693}.
     *
     * <p>Pharmacies ({@code facility:nmc:<hpid>}) are reconstructed in full from one {@code
     * getParmacyBassInfoInqire} call. Hospitals ({@code facility:hira:<ykiho>}) still answer <b>501
     * NOT_IMPLEMENTED</b>: HIRA's detail service exposes hours by {@code ykiho} but not the name,
     * address or coordinates, which come only from the coordinate-and-radius list. An unknown id is a
     * 404. See {@link FacilityService#detail}.
     */
    @GetMapping("/{id}")
    public Facility detail(@PathVariable String id) {
        return facilityService.detail(id);
    }
}
