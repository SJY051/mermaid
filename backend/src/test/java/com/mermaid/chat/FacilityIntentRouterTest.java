package com.mermaid.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.chat.dto.UiAction;
import java.util.List;
import org.junit.jupiter.api.Test;

class FacilityIntentRouterTest {

    private final FacilityIntentRouter router = new FacilityIntentRouter();

    @Test
    void routesAnExplicitOpenPharmacyRequest() {
        MermAidAnswer answer = router.route(
                "where is the closest pharmacy? opened right now",
                ServerAuthoredEmptyAnswer.answer());

        assertMap(answer, "pharmacy", true);
        assertThat(answer.summary()).contains("map below", "pharmacies");
    }

    @Test
    void routesAnExplicitHospitalLocationRequest() {
        MermAidAnswer answer = router.route(
                "Show me the nearest hospital", ServerAuthoredEmptyAnswer.answer());

        assertMap(answer, "hospital", false);
        assertThat(answer.summary()).contains("map below", "hospitals");
    }

    @Test
    void doesNotTreatMedicalAdviceAboutAHospitalAsALocationRequest() {
        MermAidAnswer original = ServerAuthoredEmptyAnswer.answer();

        MermAidAnswer answer =
                router.route("Should I go to a hospital for a fever?", original);

        assertThat(answer).isSameAs(original);
    }

    @Test
    void doesNotAddAMapToAMedicineOnlyTurn() {
        MermAidAnswer original = ServerAuthoredEmptyAnswer.answer();

        MermAidAnswer answer = router.route("I have a mild fever", original);

        assertThat(answer).isSameAs(original);
    }

    @Test
    void doesNotTreatPastTenseFacilityTextAsAnOpenNowRequest() {
        MermAidAnswer original = ServerAuthoredEmptyAnswer.answer();

        MermAidAnswer answer = router.route("I opened a pharmacy last year", original);

        assertThat(answer).isSameAs(original);
    }

    private static void assertMap(MermAidAnswer answer, String type, boolean openNow) {
        assertThat(answer.uiActions()).singleElement().satisfies(action -> {
            assertThat(action).isInstanceOf(UiAction.OpenFacilityMap.class);
            UiAction.MapPayload payload = ((UiAction.OpenFacilityMap) action).payload();
            assertThat(payload.types()).containsExactly(type);
            assertThat(payload.openNow()).isEqualTo(openNow);
            assertThat(payload.radiusM()).isEqualTo(1_000);
        });
    }
}
