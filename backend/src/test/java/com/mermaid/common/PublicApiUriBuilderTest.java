package com.mermaid.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The service key is the single most common way to lose an afternoon on data.go.kr.
 */
class PublicApiUriBuilderTest {

    @Test
    @DisplayName("a raw '+' in the service key is encoded exactly once, as %2B")
    void encodesPlusOnce() {
        String uri =
                PublicApiUriBuilder.of("https://apis.data.go.kr/X", "getThing")
                        .serviceKey("abc+def/ghi=")
                        .build()
                        .toString();

        assertThat(uri).contains("serviceKey=abc%2Bdef%2Fghi%3D");
        // The failure mode we are guarding against: %2B becoming %252B.
        assertThat(uri).doesNotContain("%25");
    }

    @Test
    @DisplayName("URLEncoder's form-encoding of space as '+' is corrected")
    void spaceIsNotAPlus() {
        String uri =
                PublicApiUriBuilder.of("https://apis.data.go.kr/X", "getThing")
                        .param("q", "sore throat")
                        .build()
                        .toString();

        // In a query string a literal '+' means a space, which would silently corrupt a key.
        assertThat(uri).contains("q=sore%20throat");
    }

    @Test
    @DisplayName("parameters keep insertion order and null ones are skipped")
    void ordersAndSkipsNulls() {
        String uri =
                PublicApiUriBuilder.of("https://apis.data.go.kr/X/", "getThing")
                        .param("a", 1)
                        .param("b", null)
                        .param("c", 3)
                        .build()
                        .toString();

        assertThat(uri).isEqualTo("https://apis.data.go.kr/X/getThing?a=1&c=3");
    }
}
