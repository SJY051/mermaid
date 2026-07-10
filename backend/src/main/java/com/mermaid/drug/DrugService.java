package com.mermaid.drug;

import com.mermaid.drug.dto.DrugSummary;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Drug lookup, and the allergy filter that FR-04 hangs on.
 *
 * <p>This needs <b>two</b> government APIs, because neither alone is enough:
 *
 * <ul>
 *   <li><b>e약은요</b> ({@code DrbEasyDrugInfoService}) — prose a human reads: what it treats, how to
 *       take it, warnings. Every field is narrative text. It has <b>no ingredient data whatsoever</b>.
 *   <li><b>의약품 제품 허가정보</b> ({@code DrugPrdtPrmsnInfoService07}) — the structured ingredients a
 *       machine filters on: {@code MAIN_ITEM_INGR} (pipe-delimited) and {@code MAIN_INGR_ENG}. The
 *       English ingredient name is what lets "I'm allergic to ibuprofen" match at all.
 * </ul>
 *
 * <p>The original 요구사항 명세서 listed only e약은요 and still demanded ingredient filtering. It could
 * not have worked. See spec §2-8.
 *
 * <p>This service is also pass 1 of the two-pass grounding described in spec §2-2: the chat flow
 * calls it to build DRUG_CONTEXT before asking the model to write its structured answer.
 */
@Service
@RequiredArgsConstructor
public class DrugService {

    /**
     * TODO(team): search e약은요 by symptom keyword, then enrich each hit with ingredients from the
     * permission API, then drop or flag anything matching {@code avoidIngredientsEn}.
     *
     * <p>Remember: e약은요 wants {@code type=json}; the pharmacy and HIRA services want {@code
     * _type=json}. One underscore, and you silently get XML.
     */
    public List<DrugSummary> search(String symptomKeyword, Set<String> avoidIngredientsEn) {
        return List.of();
    }

    /** TODO(team): merge the e약은요 narrative and the permission-API ingredients for one product. */
    public DrugSummary detail(String itemSeq) {
        throw new UnsupportedOperationException("Not implemented — see DrugService#detail");
    }
}
