package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mermaid.common.ApiException;
import com.mermaid.common.ErrorCode;
import com.mermaid.config.NaverMapProperties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class GeocodeClientTest {

    private static final String BASE_URL =
            "https://maps.apigw.ntruss.com/map-geocode/v2/geocode";

    @Test
    @DisplayName("maps Naver x/y strings to longitude/latitude and uses the subscribed host")
    void mapsCoordinatesAtTheProviderBoundary() {
        AtomicReference<ClientRequest> request = new AtomicReference<>();
        GeocodeClient client =
                client(
                        request,
                        HttpStatus.OK,
                        """
                        {"status":"OK","addresses":[
                          {"roadAddress":"서울특별시 중구 세종대로 110",
                           "jibunAddress":"서울특별시 중구 태평로1가 31",
                           "englishAddress":"110 Sejong-daero, Jung-gu, Seoul",
                           "x":"126.9783882","y":"37.5666103"}
                        ]}
                        """);

        var results = client.search("서울특별시 중구 세종대로 110");

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.latitude()).isEqualTo(37.5666103);
            assertThat(result.longitude()).isEqualTo(126.9783882);
            assertThat(result.roadAddress()).isEqualTo("서울특별시 중구 세종대로 110");
        });
        assertThat(request.get().url().getHost()).isEqualTo("maps.apigw.ntruss.com");
        assertThat(request.get().url().getPath()).isEqualTo("/map-geocode/v2/geocode");
        assertThat(request.get().headers().getFirst(GeocodeClient.API_KEY_ID_HEADER))
                .isEqualTo("client-id");
        assertThat(request.get().headers().getFirst(GeocodeClient.API_KEY_HEADER))
                .isEqualTo("server-secret");
    }

    @Test
    @DisplayName("a non-OK Naver status is SOURCE_UNAVAILABLE, never an empty result")
    void rejectsNonOkStatus() {
        GeocodeClient client =
                client(
                        new AtomicReference<>(),
                        HttpStatus.OK,
                        "{\"status\":\"SYSTEM_ERROR\",\"addresses\":[]}");

        assertThatThrownBy(() -> client.search("an address"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.SOURCE_UNAVAILABLE));
    }

    private static GeocodeClient client(
            AtomicReference<ClientRequest> request, HttpStatus status, String body) {
        WebClient webClient =
                WebClient.builder()
                        .exchangeFunction(
                                next -> {
                                    request.set(next);
                                    return Mono.just(
                                            ClientResponse.create(status)
                                                    .header(
                                                            HttpHeaders.CONTENT_TYPE,
                                                            MediaType.APPLICATION_JSON_VALUE)
                                                    .body(body)
                                                    .build());
                                })
                        .build();
        return new GeocodeClient(
                webClient,
                new NaverMapProperties("client-id", "server-secret", BASE_URL));
    }
}
