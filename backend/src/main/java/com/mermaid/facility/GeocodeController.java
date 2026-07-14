package com.mermaid.facility;

import com.mermaid.common.ApiException;
import com.mermaid.common.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Turns an address into map coordinates without exposing Naver credentials to the browser. */
@RestController
@RequestMapping("/api/v1/geocode")
@RequiredArgsConstructor
public class GeocodeController {

    private static final int MAX_QUERY_LENGTH = 200;

    private final GeocodeClient geocodeClient;

    @GetMapping
    public List<GeocodeResult> search(@RequestParam String query) {
        String trimmed = query.trim();
        if (trimmed.isBlank() || trimmed.length() > MAX_QUERY_LENGTH) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "geocode query rejected");
        }
        return geocodeClient.search(trimmed);
    }
}
