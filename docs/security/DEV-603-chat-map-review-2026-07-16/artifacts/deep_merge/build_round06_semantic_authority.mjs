import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";

// Emits an apply_patch payload. It does not write authority artifacts itself.
const deepMergeRoot = path.dirname(fileURLToPath(import.meta.url));
const artifactRoot = path.dirname(deepMergeRoot);
const scanRoot = path.dirname(artifactRoot);
const a = (...parts) => path.join(artifactRoot, ...parts);
const rel = (absolute) => path.relative(scanRoot, absolute).split(path.sep).join("/");
const sha = (bytes) => crypto.createHash("sha256").update(bytes).digest("hex");
const assert = (condition, message) => { if (!condition) throw new Error(message); };
const read = (...parts) => fs.readFileSync(a(...parts));
const readJson = (...parts) => JSON.parse(read(...parts).toString("utf8"));
const readJsonl = (...parts) => read(...parts).toString("utf8").trimEnd().split("\n").map(JSON.parse);
const jsonBytes = (value) => Buffer.from(JSON.stringify(value, null, 2) + "\n");
const unique = (values) => [...new Set(values)];
const sameSet = (left, right) => JSON.stringify([...left].sort()) === JSON.stringify([...right].sort());

const target = {
  commit: "654f906e00e81648d1482210b6a9171747dddd75",
  tree: "a14388f597c0c2a17e0dbcfc2d951a390c877214",
};
const terminalDecision = {
  state: "capped",
  reason: "capped_by_user_deadline_after_round_06",
  completed_round: "round-06",
  saturation_proven: false,
  centralized_validation_may_begin: true,
};
const classificationCounts = {
  prior_canonical_recurrence: 114,
  partial_recurrence_with_new_residue: 1,
  new_cluster_member: 3,
  suppression: 13,
};
const effectiveClassificationCounts = {
  prior_canonical_recurrence: 115,
  partial_recurrence_with_new_residue: 0,
  new_cluster_member: 3,
  suppression: 13,
};

const auditSpecs = [
  ["recurrence_parts_01_07", "round-06_recurrence_audit_01_07.json", "c03efb97f000e7096c2b45a20e8e7b04d032fa118a507b54ec8cfe0ccf861eb2"],
  ["recurrence_parts_08_14", "round-06_recurrence_audit_08_14.json", "a98377960a7a039765f00448f68c5543188ce5316a1a35554e8612b02e2a980e"],
  ["novel_residues", "round-06_novel_residue_audit_a.json", "27a706201f0473570957fa7693006ff3395c167c6c350519abfa266a63e09d84"],
  ["suppressions", "round-06_suppression_audit.json", "5af885a247d3b2e7d93116a779ea8e1214f31f0f28f52204fe54d6800572eb90"],
];
const audits = new Map();
const auditInputs = auditSpecs.map(([role, name, expectedSha]) => {
  const bytes = read("deep_merge", name);
  assert(sha(bytes) === expectedSha, "Audit hash drift: " + name);
  const value = JSON.parse(bytes.toString("utf8"));
  assert(value.target.commit === target.commit && value.target.tree === target.tree, "Audit target drift: " + name);
  const entry = { role, path: "artifacts/deep_merge/" + name, bytes: bytes.length, sha256: expectedSha };
  audits.set(role, { entry, value });
  return entry;
});

const expectedPartHashes = new Map(audits.get("novel_residues").value.semantic_part_inputs.map((entry) =>
  [entry.part_id, entry.sha256]));
const parts = [];
const allRows = [];
for (let index = 1; index <= 14; index += 1) {
  const partId = "part-" + String(index).padStart(2, "0");
  const name = partId + ".jsonl";
  const bytes = read("deep_merge", "round-06_semantic_parts", name);
  const rows = bytes.toString("utf8").trimEnd().split("\n").map(JSON.parse);
  const digest = sha(bytes);
  assert(digest === expectedPartHashes.get(partId), "Part hash drift: " + partId);
  parts.push({
    part_id: partId,
    path: "artifacts/deep_merge/round-06_semantic_parts/" + name,
    bytes: bytes.length,
    rows: rows.length,
    sha256: digest,
  });
  allRows.push(...rows);
}
assert(allRows.length === 131, "Expected 131 source rows");
const rowByKey = new Map(allRows.map((row) => [row.part_id + "|" + row.candidate_id, row]));
const observedCounts = Object.fromEntries(Object.keys(classificationCounts).map((key) => [key, 0]));
allRows.forEach((row) => { observedCounts[row.classification] += 1; });
assert(JSON.stringify(observedCounts) === JSON.stringify(classificationCounts), "Source classification drift");

const audit01 = audits.get("recurrence_parts_01_07");
const audit08 = audits.get("recurrence_parts_08_14");
const novel = audits.get("novel_residues");
const suppression = audits.get("suppressions");
assert(audit01.value.status === "complete_with_authority_overlay", "Audit 01_07 is not final");
assert(audit08.value.status === "complete_with_binding_authority_overlay", "Audit 08_14 is not final");
assert(novel.value.universe.accounted_once === true, "Novel residues are not fully accounted");
assert(suppression.value.status === "complete_all_suppressions_confirmed" &&
  suppression.value.mechanical_verification.forbidden_direct_or_partial_edges === 0 &&
  suppression.value.mechanical_verification.nonempty_new_residues === 0,
"Suppression audit is not final/context-only");

const overrideMap = new Map();
for (const verdict of audit01.value.per_row_verdicts.filter((entry) =>
  entry.verdict === "confirmed_with_edge_correction")) {
  overrideMap.set(verdict.part_id + "|" + verdict.candidate_id, {
    part_id: verdict.part_id,
    candidate_id: verdict.candidate_id,
    classification: verdict.audited_classification,
    prior_canonical_ids: verdict.audited_references.prior_canonical_ids,
    partially_recurrent_to: verdict.audited_references.partially_recurrent_to,
    related_not_subsuming_prior: verdict.audited_references.related_not_subsuming_prior,
    new_residue_keys: verdict.audited_references.new_residues.map((residue) => residue.key),
    reason: verdict.reason,
    independent_audit: { path: audit01.entry.path, sha256: audit01.entry.sha256 },
  });
}
for (const verdict of audit08.value.per_row_verdicts.filter((entry) =>
  entry.audit_verdict === "corrected_by_authority_overlay" && entry.candidate_id !== "R06W05-C009")) {
  overrideMap.set(verdict.part_id + "|" + verdict.candidate_id, {
    part_id: verdict.part_id,
    candidate_id: verdict.candidate_id,
    classification: verdict.authoritative.classification,
    prior_canonical_ids: verdict.authoritative.direct_prior_ids,
    partially_recurrent_to: verdict.authoritative.partial_prior_ids,
    related_not_subsuming_prior: verdict.authoritative.related_not_subsuming_ids,
    new_residue_keys: verdict.authoritative.new_residue_keys,
    reason: verdict.audit_reason,
    independent_audit: { path: audit08.entry.path, sha256: audit08.entry.sha256 },
  });
}
const unionDecision = novel.value.suppressed_residues[0];
assert(unionDecision.candidate_id === "R06W05-C009" &&
  JSON.stringify(unionDecision.canonical_union) === JSON.stringify(["R01-CAN-059", "R02-CAN-005"]),
"Part12 union-recurrence decision drift");
overrideMap.set("part-12|R06W05-C009", {
  part_id: "part-12",
  candidate_id: unionDecision.candidate_id,
  classification: "prior_canonical_recurrence",
  prior_canonical_ids: unionDecision.canonical_union,
  partially_recurrent_to: [],
  related_not_subsuming_prior: [],
  new_residue_keys: [],
  reason: unionDecision.reason,
  independent_audit: { path: novel.entry.path, sha256: novel.entry.sha256 },
});
const overrideOrder = [
  "part-07|R06-W03-021", "part-07|R06-W03-022", "part-07|R06-W03-023",
  "part-07|R06-W03-024", "part-07|R06-W03-025", "part-07|R06-W03-026",
  "part-07|R06-W03-027", "part-09|R06W04-C008-unbounded-chat-work",
  "part-09|R06W04-C012-pharmacy-open-now-fanout",
  "part-10|R06W04-C015-pharmacy-origin-cache-confusion",
  "part-10|R06W04-C023-invalid-allergy-value-log", "part-12|R06W05-C009",
  "part-14|R06W06-CAND-012", "part-14|R06W06-CAND-014",
];
assert(overrideMap.size === 14 && overrideOrder.every((key) => overrideMap.has(key)), "Override identity drift");
const recurrenceOverrides = overrideOrder.map((key) => overrideMap.get(key));

const effectiveRows = allRows.map((row) => {
  const override = overrideMap.get(row.part_id + "|" + row.candidate_id);
  return override ? { ...row, classification: override.classification,
    prior_canonical_ids: override.prior_canonical_ids,
    partially_recurrent_to: override.partially_recurrent_to,
    related_not_subsuming_prior: override.related_not_subsuming_prior,
    new_residues: row.new_residues.filter((residue) => override.new_residue_keys.includes(residue.key)) } : row;
});
const observedEffective = Object.fromEntries(Object.keys(effectiveClassificationCounts).map((key) => [key, 0]));
effectiveRows.forEach((row) => { observedEffective[row.classification] += 1; });
assert(JSON.stringify(observedEffective) === JSON.stringify(effectiveClassificationCounts), "Effective count drift");
const eligible = effectiveRows.filter((row) =>
  row.classification === "prior_canonical_recurrence" || row.classification === "partial_recurrence_with_new_residue");
const directCount = eligible.reduce((sum, row) => sum + row.prior_canonical_ids.length, 0);
const partialCount = eligible.reduce((sum, row) => sum + row.partially_recurrent_to.length, 0);
assert(directCount === 130 && partialCount === 6, "Effective edge count drift");

const residueDispositions = novel.value.proposed_final_cluster_order.map((cluster, index) => ({
  part_id: rowByKey.get(cluster.member.semantic_part_path.split("/").at(-1).replace(".jsonl", "") + "|" +
    cluster.member.candidate_id).part_id,
  candidate_id: cluster.member.candidate_id,
  residue_key: cluster.member.residue_key,
  decision: "promote",
  canonical_id: "R06-CAN-" + String(index + 1).padStart(3, "0"),
  reason: "Independent novel-residue audit confirmed a remediation-distinct tuple and rejected every same-round merge.",
  independently_reviewed: true,
}));
residueDispositions.push({
  part_id: "part-12",
  candidate_id: unionDecision.candidate_id,
  residue_key: unionDecision.residue_key,
  decision: "do_not_promote",
  canonical_id: null,
  reason: unionDecision.reason,
  independently_reviewed: true,
});
assert(residueDispositions.length === 4, "Residue disposition drift");

const reportPath = "artifacts/deep_merge/round-06_semantic_final_review.md";
const auditRows = auditInputs.map((entry) =>
  `- ${entry.role}: \`${entry.path}\`; ${entry.bytes} bytes; SHA-256 \`${entry.sha256}\`.`).join("\n");
const clusterSections = novel.value.proposed_final_cluster_order.map((cluster, index) => {
  const canonicalId = "R06-CAN-" + String(index + 1).padStart(3, "0");
  const locations = cluster.affected_locations.map((location) =>
    `- \`${location.path}:${location.lines}\` — ${location.label}`).join("\n");
  return `## ${canonicalId} — ${cluster.proposed_title}\n\n` +
    `Source residue: \`${cluster.member.candidate_id}\` / \`${cluster.member.residue_key}\`.\n\n` +
    `- Source: ${cluster.proof_tuple.source}\n` +
    `- Control: ${cluster.proof_tuple.control}\n` +
    `- Sink: ${cluster.proof_tuple.sink}\n` +
    `- Impact: ${cluster.proof_tuple.impact}\n` +
    `- Remediation boundary: ${cluster.remediation_boundary}\n` +
    `- Counterevidence: ${cluster.counterevidence.join(" ")}\n` +
    `- Remaining proof gaps: ${cluster.proof_gaps.length ? cluster.proof_gaps.join(" ") : "none at discovery scope"}.\n\n` +
    `Affected locations:\n\n${locations}`;
}).join("\n\n");
const reportText = `# Round 06 final semantic authority review\n\n` +
  `Status: FINAL_SEMANTIC_AUTHORITY. This is discovery reconciliation only; centralized validation and centralized attack-path analysis have not run.\n\n` +
  `## Outcome\n\n` +
  `The independent audits authorize three remediation-distinct canonical clusters. The Part12 profile-capability residue is not promoted: it is complete union recurrence to R01-CAN-059 and R02-CAN-005. Fourteen audited overlays correct edge roles without losing prior comparison context. All 13 suppressions remain context-only: they create no recurrence receipt, merge edge, or novel residue.\n\n` +
  `Materialized classifications are 114 recurrence, one partial/new, three new members, and 13 suppressions. The effective authority is 115 recurrence, zero partial/new, three new members, and 13 suppressions, with 136 recurrence receipts of which six are partial.\n\n` +
  `## Sealed audit inputs\n\n${auditRows}\n\n` +
  `The recurrence audits supply the Part07 edge corrections and the Part09, Part10, and Part14 edge corrections. The novel audit supplies the Part12 union-recurrence override and the three promotions. The suppression audit confirms all 13 suppressions and proves zero forbidden direct/partial edges and zero novel residues.\n\n` +
  `## Promoted clusters\n\n${clusterSections}\n\n` +
  `## Part12 union recurrence\n\n` +
  `\`R06W05-C009\` is absorbed only by the union of \`R01-CAN-059\` and \`R02-CAN-005\`. The first removes the concrete application-log disclosure; the second removes deviceId-only bearer authority. The claimed valid-request-target residue therefore leaves no replayable-capability remainder. Both edges are direct recurrence receipts; the former partial and related-only roles are replaced by the sealed union authority.\n\n` +
  `## Suppression boundary\n\n` +
  `All 13 suppression rows retain prior canonical IDs solely as comparison context. They do not create canonical merges or ledger receipts. The audit preserves reopen conditions and separately tracked adjacent paths without manufacturing a narrower residue.\n\n` +
  `## Phase and terminal state\n\n` +
  `Discovery is capped by the user deadline. Semantic saturation is not proven. The sealed authority permits centralized validation to begin and makes no claim that validation or centralized attack-path analysis has already occurred.\n`;
const reportBytes = Buffer.from(reportText);
const reportSha = sha(reportBytes);

const clusterRationales = [
  "Emergency-context interpretation is a distinct server-owned control; fixes to omitted affirmative patterns or generic rescue copy do not address context-driven false escalation.",
  "FORM_QUALIFIERS authority remains outside the human-signed synonym boundary; fixing signed synonym handling does not remove this in-code exact/blocking path.",
  "The outer malformed-body handler is independently reachable; remediating the nested-worktree handler in R01-CAN-066 does not change this log sink.",
];
const taxonomyByCandidate = {
  "R06-W03-009": ["CWE-20", "CWE-754"],
  "R06-W03-014": ["CWE-180", "CWE-807"],
  "R06W05-C003": ["CWE-532"],
};
const canonicalClusters = novel.value.proposed_final_cluster_order.map((cluster, index) => {
  const partId = cluster.member.semantic_part_path.split("/").at(-1).replace(".jsonl", "");
  const row = rowByKey.get(partId + "|" + cluster.member.candidate_id);
  assert(row && row.new_residues.some((residue) => residue.key === cluster.member.residue_key),
    "Novel cluster source drift: " + cluster.member.candidate_id);
  const sourceRecord = readJsonl(...row.source_file.replace("artifacts/", "").split("/"))[row.source_line - 1];
  const sourceVariant = sourceRecord.taxonomy != null ? { taxonomy: sourceRecord.taxonomy } :
    sourceRecord.category != null ? { category: sourceRecord.category } : {};
  return {
    canonical_id: "R06-CAN-" + String(index + 1).padStart(3, "0"),
    semantic_cluster_id: "R06-NEW-" + String(index + 1).padStart(3, "0"),
    title: cluster.proposed_title,
    residue_refs: [{ part_id: partId, candidate_id: cluster.member.candidate_id,
      residue_key: cluster.member.residue_key }],
    instance_key: row.instance_key,
    proof_tuple: cluster.proof_tuple,
    remediation_boundary: cluster.remediation_boundary,
    rationale: clusterRationales[index],
    partial_prior_ids: row.partially_recurrent_to,
    related_not_subsuming_ids: row.related_not_subsuming_prior,
    affected_locations: cluster.affected_locations,
    counterevidence: cluster.counterevidence,
    taxonomy: { CWE: taxonomyByCandidate[cluster.member.candidate_id] },
    taxonomy_variants: [sourceVariant],
    validation_recommended: true,
    independent_approval: {
      approved: true,
      independent_of_part_authors: true,
      review_artifact: reportPath,
      review_artifact_sha256: reportSha,
      decision_reason: "The sealed independent novel-residue audit confirms strict non-subsumption and the review rejects every same-round merge.",
    },
  };
});
assert(canonicalClusters.length === 3, "Expected exactly three canonical clusters");

const authority = {
  schema_version: "deep-security-round-06-semantic-authority-v1",
  target,
  strict_merge_rule: "Merge only when complete remediation subsumption is proven; related-only context never creates a merge edge or recurrence receipt.",
  audit_inputs: auditInputs,
  phase_boundary: {
    centralized_validation_performed: false,
    centralized_attack_path_analysis_performed: false,
  },
  terminal_decision: terminalDecision,
  source_row_count: 131,
  classification_counts: classificationCounts,
  effective_classification_counts: effectiveClassificationCounts,
  recurrence_edge_count: directCount + partialCount,
  partial_recurrence_edge_count: partialCount,
  recurrence_overrides: recurrenceOverrides,
  residue_dispositions: residueDispositions,
  canonical_clusters: canonicalClusters,
};
const authorityPath = "artifacts/deep_merge/round-06_semantic_final_authority.json";
const authorityBytes = jsonBytes(authority);
const authoritySha = sha(authorityBytes);
const manifest = {
  schema_version: "deep-security-round-06-semantic-authority-manifest-v1",
  target,
  frozen_canonical: {
    path: "artifacts/04_reconciliation/deduped_candidates.jsonl",
    bytes: 3954212,
    rows: 185,
    sha256: "0d5b39b868863b55edc96ff71ef2126b01917b102c1aa7c3ff6c1a49c16bc8a8",
  },
  prior_canonical_ledgers: {
    algorithm: "sha256 of ordered lines: <file-sha256><two spaces><scan-relative-path><newline>",
    count: 185,
    rows: 1268,
    bytes: 8444259,
    sha256: "6636f5b33e3ac8909631b442e8f82c2bed540d8436c2f0aad9d6845c7c214096",
  },
  semantic_authority: { path: authorityPath, bytes: authorityBytes.length, sha256: authoritySha },
  semantic_report: { path: reportPath, bytes: reportBytes.length, sha256: reportSha },
  parts,
  source_row_count: 131,
  classification_counts: classificationCounts,
  terminal_decision: terminalDecision,
};
const manifestPath = "artifacts/deep_merge/round-06_semantic_final_authority_manifest.json";
const manifestBytes = jsonBytes(manifest);

const additions = [
  [path.join(scanRoot, reportPath), reportBytes],
  [path.join(scanRoot, authorityPath), authorityBytes],
  [path.join(scanRoot, manifestPath), manifestBytes],
];
let patch = "*** Begin Patch\n";
for (const [file, bytes] of additions) {
  assert(!fs.existsSync(file), "Refusing to replace existing final authority artifact: " + rel(file));
  patch += "*** Add File: " + file + "\n";
  for (const line of bytes.toString("utf8").split("\n").slice(0, -1)) patch += "+" + line + "\n";
}
patch += "*** End Patch\n";
process.stdout.write(patch);
