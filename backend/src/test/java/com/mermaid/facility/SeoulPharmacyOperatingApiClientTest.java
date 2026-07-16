package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mermaid.common.PublicApiException;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.SeoulPharmacyOperatingProperties;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class SeoulPharmacyOperatingApiClientTest {

    @Test
    @DisplayName("reads every page locally because the Seoul API does not filter HPID server-side")
    void readsEveryPageAndIndexesByExactHpid() {
        List<URI> requests = new CopyOnWriteArrayList<>();
        WebClient webClient =
                WebClient.builder()
                        .exchangeFunction(
                                request -> {
                                    requests.add(request.url());
                                    boolean first = request.url().getPath().endsWith("/1/1000");
                                    return Mono.just(xmlResponse(first ? "C1111111" : "C2222222", 5494));
                                })
                        .build();
        SeoulPharmacyOperatingApiClient client = liveClient(webClient);

        var table = client.operatingTable();

        assertThat(requests)
                .extracting(URI::getPath)
                .containsExactly(
                        "/key/xml/TbPharmacyOperateInfo/1/1000",
                        "/key/xml/TbPharmacyOperateInfo/1001/2000",
                        "/key/xml/TbPharmacyOperateInfo/2001/3000",
                        "/key/xml/TbPharmacyOperateInfo/3001/4000",
                        "/key/xml/TbPharmacyOperateInfo/4001/5000",
                        "/key/xml/TbPharmacyOperateInfo/5001/6000");
        assertThat(table.forHpid("C1111111").weeklyHours().byDay().get(1))
                .containsExactly("0830", "1900");
        assertThat(table.forHpid("C1111111").scheduleUpdatedAt())
                .isEqualTo(Instant.parse("2026-06-20T08:40:03Z"));
        // Deleting the local HPID lookup would turn this into a name/address guess; it must stay null.
        assertThat(table.forHpid("C9999999")).isNull();
    }

    @Test
    @DisplayName("removes ambiguous duplicate HPIDs instead of choosing an arbitrary schedule")
    void removesDuplicateHpidRows() {
        SeoulPharmacyOperatingApiClient client = liveClient(null);

        var page =
                client.parsePage(
                        envelope(
                                2,
                                row("C1111111", "0830", "1900")
                                        + row("C1111111", "0900", "1800")));

        var table = SeoulPharmacyOperatingApiClient.OperatingTable.index(page.rows(), Instant.EPOCH);
        assertThat(table.forHpid("C1111111")).isNull();
    }

    @Test
    @DisplayName("marks a malformed day unavailable instead of turning it into a closed interval")
    void malformedDayIsUnavailable() {
        SeoulPharmacyOperatingApiClient client = liveClient(null);

        var page =
                client.parsePage(
                        envelope(
                                1,
                                """
                                <row>
                                  <HPID>C1111111</HPID>
                                  <DUTYTIME1S>0830</DUTYTIME1S><DUTYTIME1C>1900</DUTYTIME1C>
                                  <DUTYTIME5S>2500</DUTYTIME5S><DUTYTIME5C>1700</DUTYTIME5C>
                                </row>
                                """));

        var row = page.rows().getFirst();
        assertThat(row.weeklyHours().byDay()).containsKey(1).doesNotContainKey(5);
        assertThat(row.unavailableDays()).contains(5);
    }

    @Test
    @DisplayName("rejects a non-success envelope without retaining its provider message")
    void rejectsNonSuccessEnvelope() {
        SeoulPharmacyOperatingApiClient client = liveClient(null);

        assertThatThrownBy(
                        () ->
                                client.parsePage(
                                        "<TbPharmacyOperateInfo><RESULT><CODE>ERROR-500</CODE>"
                                                + "<MESSAGE>secret-bearing upstream detail</MESSAGE></RESULT>"
                                                + "</TbPharmacyOperateInfo>"))
                .isInstanceOf(PublicApiException.class)
                .hasMessage("Seoul pharmacy operating-hours API returned a non-success response")
                .hasNoCause();
    }

    private static SeoulPharmacyOperatingApiClient liveClient(WebClient webClient) {
        return new SeoulPharmacyOperatingApiClient(
                webClient,
                new SeoulPharmacyOperatingProperties(
                        "https://seoul.example/key/xml/TbPharmacyOperateInfo/1/5"),
                new DataModeProperties(DataModeProperties.DataMode.LIVE));
    }

    private static ClientResponse xmlResponse(String hpid, int total) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_XML_VALUE)
                .body(envelope(total, row(hpid, "0830", "1900")))
                .build();
    }

    private static String envelope(int total, String rows) {
        return """
                <TbPharmacyOperateInfo>
                  <list_total_count>%d</list_total_count>
                  <RESULT><CODE>INFO-000</CODE><MESSAGE>success</MESSAGE></RESULT>
                  %s
                </TbPharmacyOperateInfo>
                """.formatted(total, rows);
    }

    private static String row(String hpid, String start, String end) {
        return """
                <row>
                  <HPID>%s</HPID><DUTYTIME1S>%s</DUTYTIME1S><DUTYTIME1C>%s</DUTYTIME1C>
                  <WORK_DTTM>2026-06-20 17:40:03.0</WORK_DTTM>
                </row>
                """.formatted(hpid, start, end);
    }
}
