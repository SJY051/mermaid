#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { execFileSync } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";

const SCRIPT_PATH = fileURLToPath(import.meta.url);
const OUTPUT_DIR = path.dirname(SCRIPT_PATH);
const SCAN_ROOT = path.resolve(OUTPUT_DIR, "../..");
const REPO_ROOT = "/Users/asqi/Developer/mermaid";

const TARGET_COMMIT = "654f906e00e81648d1482210b6a9171747dddd75";
const TARGET_TREE = "a14388f597c0c2a17e0dbcfc2d951a390c877214";
const CANONICAL_PATH = "artifacts/04_reconciliation/deduped_candidates.jsonl";
const CANONICAL_SHA256 = "274fd61156b7ae3ebeadb1c905258fbe001a5f81380891b9d2491b44652477b8";
const CANONICAL_ROWS = 188;
const VALIDATION_RUN_ID = "central-validation-round-06";

const VALIDATION_KEYS = [
  "schema_version",
  "event",
  "candidate_id",
  "canonical_line",
  "canonical_row_sha256",
  "target",
  "disposition",
  "severity",
  "confidence",
  "source",
  "closest_control",
  "sink",
  "impact",
  "preconditions",
  "counterevidence",
  "proof_gaps",
  "evidence",
  "affected_locations",
  "fix_direction",
  "review_tier",
  "rationale",
  "canonical_ledger_path",
  "canonical_ledger_sha256",
];
const EVIDENCE_KEYS = ["path", "lines", "claim"];
const DISPOSITIONS = ["reportable", "not_reportable", "needs_review"];
const SEVERITIES = ["P0", "P1", "P2", "P3", "none"];

const BATCHES = [
  {
    id: "batch-01", start: 1, end: 38, validator: "validate_chat_controls",
    validationSha256: "7c6b132d11a0c3644c2e169ff5f12183faff22e19d6a5e715dc98c208680d358",
    reportSha256: "b34688ab1c67bc6bf866f84cdf0bc04a39afa36fadd43416deda310313be4023",
  },
  {
    id: "batch-02", start: 39, end: 76, validator: "validate_allergy_output",
    validationSha256: "ed5af1803d829d0623097506b172fb0ed3da6e1ea98a4944754620ed628976ed",
    reportSha256: "e0a3c339cbe64fbc4987092ce9883b5570e46709e26e5edf2cfd7cefaad86d8e",
  },
  {
    id: "batch-03", start: 77, end: 113, validator: "validate_availability",
    validationSha256: "1a9d0428b08304586a2853897998d6d69a43e9a200c8b38e9fda190cb94e7bac",
    reportSha256: "8196371138844ec7851249d2092ead1d807c37fc032bf6282dc5922aa70d7fe3",
  },
  {
    id: "batch-04", start: 114, end: 151, validator: "merge_r04_semantic",
    validationSha256: "d5392b53f366566817786ebd1807dccc20b0ad3206e390b620958e2f1b7cd805",
    reportSha256: "2e76a815bbaaa62d3a5a1f0eccec62afa5e1e0de55a707afd4805c3548c380e0",
  },
  {
    id: "batch-05", start: 152, end: 188, validator: "merge_r05_semantic",
    validationSha256: "83c67554861df3ed6d506a772eb614dc4d6b5a3dc459c827d71fd4b73b06b28f",
    reportSha256: "04e95e9625857806d09dd944c9ac6df1faa68e6957492b6f37e4216909d4f313",
  },
].map((batch) => ({
  ...batch,
  validationPath: `artifacts/central_validation_round06/${batch.id}/validation.jsonl`,
  reportPath: `artifacts/central_validation_round06/${batch.id}/report.md`,
}));

function fail(message) {
  throw new Error(message);
}

function assert(condition, message) {
  if (!condition) fail(message);
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function asAbsolute(relativePath) {
  const absolutePath = path.resolve(SCAN_ROOT, relativePath);
  const relative = path.relative(SCAN_ROOT, absolutePath);
  assert(relative !== ".." && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative),
    `path escapes scan root: ${relativePath}`);
  return absolutePath;
}

function readRequired(relativePath) {
  const absolutePath = asAbsolute(relativePath);
  assert(fs.existsSync(absolutePath), `missing required input: ${relativePath}`);
  const bytes = fs.readFileSync(absolutePath);
  assert(bytes.length > 0, `required input is empty: ${relativePath}`);
  return bytes;
}

function parseStrictJsonl(relativePath, bytes) {
  assert(bytes.at(-1) === 0x0a, `${relativePath} must end with LF`);
  assert(!bytes.includes(0x0d), `${relativePath} must not contain CR bytes`);
  const rawLineBytes = [];
  let start = 0;
  for (let index = 0; index < bytes.length; index += 1) {
    if (bytes[index] === 0x0a) {
      rawLineBytes.push(bytes.subarray(start, index));
      start = index + 1;
    }
  }
  const rawLines = rawLineBytes.map((lineBytes, index) => {
    assert(lineBytes.length > 0, `${relativePath}:${index + 1} is a blank JSONL row`);
    const line = lineBytes.toString("utf8");
    assert(Buffer.from(line).equals(lineBytes), `${relativePath}:${index + 1} is not valid UTF-8`);
    return line;
  });
  assert(rawLines.length > 0, `${relativePath} has no JSONL rows`);
  const rows = rawLines.map((line, index) => {
    try {
      return JSON.parse(line);
    } catch (error) {
      fail(`${relativePath}:${index + 1} is invalid JSON: ${error.message}`);
    }
  });
  return { rawLineBytes, rawLines, rows };
}

function exactKeys(value, keys, label) {
  assert(value !== null && typeof value === "object" && !Array.isArray(value), `${label} must be an object`);
  const actual = Object.keys(value);
  assert(actual.length === keys.length && actual.every((key, index) => key === keys[index]),
    `${label} keys/order mismatch: expected ${keys.join(",")}; got ${actual.join(",")}`);
}

function nonemptyString(value, label) {
  assert(typeof value === "string" && value.trim().length > 0, `${label} must be a nonempty string`);
}

function stringArray(value, label) {
  assert(Array.isArray(value), `${label} must be an array`);
  value.forEach((item, index) => nonemptyString(item, `${label}[${index}]`));
}

function validateEvidence(value, label) {
  assert(Array.isArray(value), `${label} must be an array`);
  value.forEach((item, index) => {
    exactKeys(item, EVIDENCE_KEYS, `${label}[${index}]`);
    EVIDENCE_KEYS.forEach((key) => nonemptyString(item[key], `${label}[${index}].${key}`));
  });
}

function validateAffectedLocations(value, label) {
  assert(Array.isArray(value), `${label} must be an array`);
  value.forEach((item, index) => {
    assert(item !== null && typeof item === "object" && !Array.isArray(item), `${label}[${index}] must be an object`);
    nonemptyString(item.path, `${label}[${index}].path`);
    nonemptyString(item.lines, `${label}[${index}].lines`);
    if (Object.hasOwn(item, "label")) assert(typeof item.label === "string", `${label}[${index}].label must be a string`);
    if (Object.hasOwn(item, "detail")) assert(typeof item.detail === "string", `${label}[${index}].detail must be a string`);
  });
}

function git(args, options = {}) {
  return execFileSync("git", ["-C", REPO_ROOT, ...args], {
    encoding: "utf8",
    env: { ...process.env, GIT_OPTIONAL_LOCKS: "0" },
    stdio: options.stdio ?? ["ignore", "pipe", "pipe"],
  });
}

function gitQuiet(args) {
  try {
    git(args);
    return true;
  } catch (error) {
    if (error.status === 1) return false;
    throw error;
  }
}

function captureRepositoryState() {
  assert(gitQuiet(["diff", "--quiet"]), "repository has tracked unstaged changes");
  assert(gitQuiet(["diff", "--cached", "--quiet"]), "repository has staged changes");

  const targetType = git(["cat-file", "-t", TARGET_COMMIT]).trim();
  assert(targetType === "commit", `target object is not a commit: ${TARGET_COMMIT}`);
  const targetTree = git(["rev-parse", `${TARGET_COMMIT}^{tree}`]).trim();
  assert(targetTree === TARGET_TREE, `target tree mismatch: expected ${TARGET_TREE}; got ${targetTree}`);

  const porcelain = git(["status", "--porcelain=v1", "--untracked-files=all"]);
  const head = git(["rev-parse", "HEAD"]).trim();
  const headTree = git(["rev-parse", "HEAD^{tree}"]).trim();
  return {
    path: REPO_ROOT,
    observed_head: head,
    observed_head_tree: headTree,
    target_commit_object_verified: true,
    target_tree_verified: true,
    tracked_clean: true,
    staged_clean: true,
    porcelain_sha256: sha256(Buffer.from(porcelain)),
    porcelain_lines: porcelain.length === 0 ? [] : porcelain.trimEnd().split("\n"),
    rawPorcelain: porcelain,
  };
}

function parseArgs(argv) {
  let mode = "check-only";
  let modeWasExplicit = false;
  let manifestSha256 = null;
  for (const arg of argv) {
    if (arg === "--check-only" || arg === "--write") {
      const selected = arg.slice(2);
      assert(!modeWasExplicit || mode === selected, "--check-only and --write are mutually exclusive");
      mode = selected;
      modeWasExplicit = true;
    } else if (arg.startsWith("--manifest-sha256=")) {
      assert(manifestSha256 === null, "--manifest-sha256 may be supplied only once");
      manifestSha256 = arg.slice("--manifest-sha256=".length);
      assert(/^[a-f0-9]{64}$/.test(manifestSha256), "--manifest-sha256 must be a lowercase SHA-256");
    } else {
      fail(`unknown argument: ${arg}`);
    }
  }
  if (mode === "write") {
    assert(manifestSha256 !== null, "--write requires --manifest-sha256=<check-only planned_manifest_sha256>");
  } else {
    assert(manifestSha256 === null, "--manifest-sha256 is valid only with --write");
  }
  return { mode, manifestSha256 };
}

function countsBy(values, preferredOrder = []) {
  const counts = new Map();
  values.forEach((value) => counts.set(value, (counts.get(value) ?? 0) + 1));
  const keys = [
    ...preferredOrder.filter((key) => counts.has(key)),
    ...[...counts.keys()].filter((key) => !preferredOrder.includes(key)).sort(),
  ];
  return Object.fromEntries(keys.map((key) => [key, counts.get(key)]));
}

function jsonBytes(value) {
  return Buffer.from(`${JSON.stringify(value, null, 2)}\n`);
}

function jsonlRowBytes(value) {
  return Buffer.from(`${JSON.stringify(value)}\n`);
}

function markdownCell(value) {
  return String(value).replaceAll("\\", "\\\\").replaceAll("|", "\\|").replaceAll("\r", " ").replaceAll("\n", "<br>");
}

function writeAtomicallyWithRollback(outputMap, expectedHashes, permittedCurrentStates, postWriteCheck) {
  const changedEntries = [...outputMap.entries()].filter(([relativePath, plannedBytes]) => {
    const absolutePath = asAbsolute(relativePath);
    return !fs.existsSync(absolutePath) || !fs.readFileSync(absolutePath).equals(plannedBytes);
  });
  if (changedEntries.length === 0) {
    postWriteCheck();
    return;
  }

  const snapshots = new Map();
  const stagedTemps = new Map();
  const committedPaths = new Set();
  for (const [relativePath] of changedEntries) {
    const absolutePath = asAbsolute(relativePath);
    assert(fs.existsSync(path.dirname(absolutePath)), `output directory is missing: ${path.dirname(relativePath)}`);
    snapshots.set(relativePath, fs.existsSync(absolutePath) ? {
      bytes: fs.readFileSync(absolutePath),
      mode: fs.statSync(absolutePath).mode & 0o777,
    } : null);
    const snapshot = snapshots.get(relativePath);
    const state = snapshot === null ? null : sha256(snapshot.bytes);
    const permitted = permittedCurrentStates.get(relativePath);
    assert(permitted !== undefined && permitted.includes(state),
      `refusing to overwrite unexpected state at ${relativePath}: ${state ?? "missing"}`);
  }

  try {
    for (const [relativePath, plannedBytes] of changedEntries) {
      const absolutePath = asAbsolute(relativePath);
      const tempPath = path.join(path.dirname(absolutePath), `.${path.basename(absolutePath)}.${process.pid}.${randomUUID()}.tmp`);
      const outputMode = snapshots.get(relativePath)?.mode ?? 0o644;
      fs.writeFileSync(tempPath, plannedBytes, { flag: "wx", mode: outputMode });
      const descriptor = fs.openSync(tempPath, "r");
      try {
        fs.fsyncSync(descriptor);
      } finally {
        fs.closeSync(descriptor);
      }
      stagedTemps.set(relativePath, tempPath);
    }

    for (const [relativePath] of changedEntries) {
      const absolutePath = asAbsolute(relativePath);
      const snapshot = snapshots.get(relativePath);
      if (snapshot === null) {
        assert(!fs.existsSync(absolutePath), `output appeared concurrently before rename: ${relativePath}`);
      } else {
        assert(fs.existsSync(absolutePath), `output disappeared concurrently before rename: ${relativePath}`);
        assert(fs.readFileSync(absolutePath).equals(snapshot.bytes), `output changed concurrently before rename: ${relativePath}`);
        assert((fs.statSync(absolutePath).mode & 0o777) === snapshot.mode,
          `output mode changed concurrently before rename: ${relativePath}`);
      }
      fs.renameSync(stagedTemps.get(relativePath), asAbsolute(relativePath));
      stagedTemps.delete(relativePath);
      committedPaths.add(relativePath);
    }

    for (const [relativePath, plannedSha] of expectedHashes) {
      const actualSha = sha256(readRequired(relativePath));
      assert(actualSha === plannedSha, `post-write hash mismatch for ${relativePath}: expected ${plannedSha}; got ${actualSha}`);
    }
    postWriteCheck();
  } catch (error) {
    const rollbackFailures = [];
    for (const tempPath of stagedTemps.values()) {
      try {
        if (fs.existsSync(tempPath)) fs.unlinkSync(tempPath);
      } catch (rollbackError) {
        rollbackFailures.push(`remove ${tempPath}: ${rollbackError.message}`);
      }
    }
    for (const relativePath of committedPaths) {
      const original = snapshots.get(relativePath);
      try {
        const absolutePath = asAbsolute(relativePath);
        if (original === null) {
          if (fs.existsSync(absolutePath)) fs.unlinkSync(absolutePath);
        } else {
          const restorePath = path.join(path.dirname(absolutePath), `.${path.basename(absolutePath)}.${process.pid}.${randomUUID()}.restore`);
          fs.writeFileSync(restorePath, original.bytes, { flag: "wx", mode: original.mode });
          fs.renameSync(restorePath, absolutePath);
        }
      } catch (rollbackError) {
        rollbackFailures.push(`restore ${relativePath}: ${rollbackError.message}`);
      }
    }
    for (const relativePath of committedPaths) {
      const original = snapshots.get(relativePath);
      try {
        const absolutePath = asAbsolute(relativePath);
        if (original === null) {
          assert(!fs.existsSync(absolutePath), `rollback left newly created output: ${relativePath}`);
        } else {
          assert(fs.existsSync(absolutePath), `rollback removed existing output: ${relativePath}`);
          assert(fs.readFileSync(absolutePath).equals(original.bytes), `rollback byte mismatch: ${relativePath}`);
          assert((fs.statSync(absolutePath).mode & 0o777) === original.mode, `rollback mode mismatch: ${relativePath}`);
        }
      } catch (rollbackError) {
        rollbackFailures.push(`verify ${relativePath}: ${rollbackError.message}`);
      }
    }
    if (rollbackFailures.length > 0) {
      fail(`${error.message}; rollback failures: ${rollbackFailures.join("; ")}`);
    }
    throw error;
  }
}

function buildPlan(repositoryState) {
  const canonicalBytes = readRequired(CANONICAL_PATH);
  const actualCanonicalSha = sha256(canonicalBytes);
  assert(actualCanonicalSha === CANONICAL_SHA256,
    `canonical stream hash mismatch: expected ${CANONICAL_SHA256}; got ${actualCanonicalSha}`);
  const canonical = parseStrictJsonl(CANONICAL_PATH, canonicalBytes);
  assert(canonical.rows.length === CANONICAL_ROWS,
    `canonical row count mismatch: expected ${CANONICAL_ROWS}; got ${canonical.rows.length}`);

  const canonicalIds = canonical.rows.map((row, index) => {
    nonemptyString(row.candidate_id, `${CANONICAL_PATH}:${index + 1}.candidate_id`);
    return row.candidate_id;
  });
  assert(new Set(canonicalIds).size === CANONICAL_ROWS, "canonical candidate IDs are not unique");
  const canonicalRowHashes = canonical.rawLineBytes.map((lineBytes) => sha256(lineBytes));

  const batchSeals = [];
  const batchReportSections = [];
  const validationBatchBytes = [];
  const validationEntries = [];
  const inputSeals = new Map([[CANONICAL_PATH, CANONICAL_SHA256]]);

  for (const batch of BATCHES) {
    const expectedRows = batch.end - batch.start + 1;
    const validationBytes = readRequired(batch.validationPath);
    const reportBytes = readRequired(batch.reportPath);
    const validationSha = sha256(validationBytes);
    const reportSha = sha256(reportBytes);
    assert(validationSha === batch.validationSha256,
      batch.validationPath + " differs from the final frozen validation SHA-256");
    assert(reportSha === batch.reportSha256,
      batch.reportPath + " differs from the final frozen report SHA-256");
    const reportText = reportBytes.toString("utf8");
    assert(Buffer.from(reportText).equals(reportBytes), `${batch.reportPath} is not valid UTF-8`);
    inputSeals.set(batch.validationPath, validationSha);
    inputSeals.set(batch.reportPath, reportSha);
    const parsed = parseStrictJsonl(batch.validationPath, validationBytes);
    validationBatchBytes.push(validationBytes);
    assert(parsed.rows.length === expectedRows,
      `${batch.validationPath} row count mismatch: expected ${expectedRows}; got ${parsed.rows.length}`);

    parsed.rows.forEach((row, localIndex) => {
      const canonicalLine = batch.start + localIndex;
      const label = `${batch.validationPath}:${localIndex + 1}`;
      exactKeys(row, VALIDATION_KEYS, label);
      assert(row.schema_version === "deep-security-central-validation-v1", `${label}.schema_version mismatch`);
      assert(row.event === "centralized_validation_receipt", `${label}.event mismatch`);
      nonemptyString(row.candidate_id, `${label}.candidate_id`);
      assert(Number.isInteger(row.canonical_line) && row.canonical_line === canonicalLine,
        `${label}.canonical_line must equal ${canonicalLine}`);
      assert(row.candidate_id === canonicalIds[canonicalLine - 1],
        `${label}.candidate_id mismatch: expected ${canonicalIds[canonicalLine - 1]}; got ${row.candidate_id}`);
      assert(/^[a-f0-9]{64}$/.test(row.canonical_row_sha256), `${label}.canonical_row_sha256 must be lowercase SHA-256`);
      assert(row.canonical_row_sha256 === canonicalRowHashes[canonicalLine - 1],
        `${label}.canonical_row_sha256 mismatch`);
      assert(row.target === TARGET_COMMIT, `${label}.target mismatch`);
      assert(DISPOSITIONS.includes(row.disposition), `${label}.disposition is outside the enum`);
      assert(SEVERITIES.includes(row.severity), `${label}.severity is outside the enum`);
      assert(typeof row.confidence === "number" && Number.isFinite(row.confidence)
        && row.confidence >= 0 && row.confidence <= 1, `${label}.confidence must be a number from 0 through 1`);
      ["source", "closest_control", "sink", "impact", "fix_direction", "review_tier", "rationale"]
        .forEach((key) => nonemptyString(row[key], `${label}.${key}`));
      ["preconditions", "counterevidence", "proof_gaps"]
        .forEach((key) => stringArray(row[key], `${label}.${key}`));
      validateEvidence(row.evidence, `${label}.evidence`);
      validateAffectedLocations(row.affected_locations, `${label}.affected_locations`);
      const expectedLedgerPath = `artifacts/05_findings/${row.candidate_id}/candidate_ledger.jsonl`;
      assert(row.canonical_ledger_path === expectedLedgerPath,
        `${label}.canonical_ledger_path mismatch: expected ${expectedLedgerPath}; got ${row.canonical_ledger_path}`);
      assert(/^[a-f0-9]{64}$/.test(row.canonical_ledger_sha256), `${label}.canonical_ledger_sha256 must be lowercase SHA-256`);

      validationEntries.push({
        batch,
        batchLine: localIndex + 1,
        canonicalLine,
        row,
        rawLineBytes: parsed.rawLineBytes[localIndex],
        validationFileSha256: validationSha,
      });
    });

    batchSeals.push({
      batch_id: batch.id,
      validator: batch.validator,
      canonical_lines: { start: batch.start, end: batch.end },
      expected_rows: expectedRows,
      validation: { path: batch.validationPath, bytes: validationBytes.length, sha256: validationSha, rows: parsed.rows.length },
      report: { path: batch.reportPath, bytes: reportBytes.length, sha256: reportSha },
    });
    batchReportSections.push({ batchId: batch.id, path: batch.reportPath, sha256: reportSha, text: reportText.trimEnd() });
  }

  assert(validationEntries.length === CANONICAL_ROWS,
    `validation row count mismatch: expected ${CANONICAL_ROWS}; got ${validationEntries.length}`);
  const validationIds = validationEntries.map((entry) => entry.row.candidate_id);
  const validationLines = validationEntries.map((entry) => entry.row.canonical_line);
  assert(new Set(validationIds).size === CANONICAL_ROWS, "validation candidate IDs are not unique");
  assert(new Set(validationLines).size === CANONICAL_ROWS, "validation canonical lines are not unique");
  validationLines.forEach((line, index) => assert(line === index + 1, `validation line gap or reorder at assembled row ${index + 1}`));
  validationIds.forEach((id, index) => assert(id === canonicalIds[index], `validation ID order mismatch at line ${index + 1}`));

  const ledgerPlans = [];
  const ledgerSeals = new Map();
  const ledgerPathSet = new Set();
  const outputMap = new Map();
  let ledgerReceiptsAlreadyPresent = 0;

  for (const entry of validationEntries) {
    const { row } = entry;
    const ledgerPath = row.canonical_ledger_path;
    assert(!ledgerPathSet.has(ledgerPath), `duplicate canonical ledger path: ${ledgerPath}`);
    ledgerPathSet.add(ledgerPath);
    const currentBytes = readRequired(ledgerPath);
    const parsedLedger = parseStrictJsonl(ledgerPath, currentBytes);
    parsedLedger.rows.forEach((ledgerRow, index) => {
      if (Object.hasOwn(ledgerRow, "canonical_candidate_id")) {
        assert(ledgerRow.canonical_candidate_id === row.candidate_id,
          `${ledgerPath}:${index + 1}.canonical_candidate_id mismatch`);
      }
    });
    const markedIndexes = parsedLedger.rows
      .map((ledgerRow, index) => ledgerRow.validation_run_id === VALIDATION_RUN_ID ? index : -1)
      .filter((index) => index >= 0);
    assert(markedIndexes.length <= 1, `${ledgerPath} contains duplicate ${VALIDATION_RUN_ID} receipts`);
    if (markedIndexes.length === 1) {
      assert(markedIndexes[0] === parsedLedger.rows.length - 1,
        `${ledgerPath} has a ${VALIDATION_RUN_ID} receipt that is not the final row`);
      ledgerReceiptsAlreadyPresent += 1;
    }

    const baseRawLines = markedIndexes.length === 1 ? parsedLedger.rawLines.slice(0, -1) : parsedLedger.rawLines;
    assert(baseRawLines.length > 0, `${ledgerPath} has no historical base rows`);
    const baseBytes = Buffer.from(`${baseRawLines.join("\n")}\n`);
    const baseSha = sha256(baseBytes);
    assert(baseSha === row.canonical_ledger_sha256,
      `${ledgerPath} pre-append hash mismatch: validator captured ${row.canonical_ledger_sha256}; base is ${baseSha}`);

    const receipt = {
      schema_version: "deep-security-scan-candidate-ledger-v1",
      event: "canonical_validation_receipt",
      phase: "validation",
      validation_run_id: VALIDATION_RUN_ID,
      canonical_candidate_id: row.candidate_id,
      target: TARGET_COMMIT,
      target_tree: TARGET_TREE,
      canonical_line: row.canonical_line,
      canonical_row_sha256: row.canonical_row_sha256,
      canonical_ledger_path: row.canonical_ledger_path,
      canonical_ledger_preappend_sha256: row.canonical_ledger_sha256,
      source_validation_artifact: {
        path: entry.batch.validationPath,
        batch_id: entry.batch.id,
        line: entry.batchLine,
        canonical_line: entry.canonicalLine,
        row_sha256: sha256(entry.rawLineBytes),
        file_sha256: entry.validationFileSha256,
      },
      validation_disposition: row.disposition,
      severity: row.severity,
      confidence: row.confidence,
      source_validation: row,
      centralized_validation_performed: true,
      centralized_attack_path_analysis_performed: false,
    };
    const receiptBytes = jsonlRowBytes(receipt);
    const plannedBytes = Buffer.concat([baseBytes, receiptBytes]);
    if (markedIndexes.length === 1) {
      const currentReceiptBytes = Buffer.from(`${parsedLedger.rawLines.at(-1)}\n`);
      assert(currentReceiptBytes.equals(receiptBytes), `${ledgerPath} existing ${VALIDATION_RUN_ID} receipt differs from the plan`);
      assert(currentBytes.equals(plannedBytes), `${ledgerPath} has unexpected bytes around its existing receipt`);
    }

    outputMap.set(ledgerPath, plannedBytes);
    ledgerSeals.set(ledgerPath, { preappendSha256: baseSha, plannedSha256: sha256(plannedBytes) });
    ledgerPlans.push({
      candidate_id: row.candidate_id,
      path: ledgerPath,
      preappend_sha256: baseSha,
      planned_sha256: sha256(plannedBytes),
      receipt_row_sha256: sha256(receiptBytes.subarray(0, receiptBytes.length - 1)),
    });
  }

  const validationStreamPath = "artifacts/central_validation_round06/validation.jsonl";
  const validationStreamBytes = Buffer.concat(validationBatchBytes);
  const validationStreamSha = sha256(validationStreamBytes);
  outputMap.set(validationStreamPath, validationStreamBytes);

  const dispositions = countsBy(validationEntries.map((entry) => entry.row.disposition), DISPOSITIONS);
  const severities = countsBy(validationEntries.map((entry) => entry.row.severity), SEVERITIES);
  const reviewTiers = countsBy(validationEntries.map((entry) => entry.row.review_tier));
  const confidenceValues = validationEntries.map((entry) => entry.row.confidence);
  const confidence = {
    minimum: Math.min(...confidenceValues),
    maximum: Math.max(...confidenceValues),
    average: confidenceValues.reduce((sum, value) => sum + value, 0) / confidenceValues.length,
  };

  const summaryPath = "artifacts/central_validation_round06/validation_summary.json";
  const summary = {
    schema_version: "deep-security-central-validation-summary-v1",
    event: "central_validation_assembly_summary",
    validation_run_id: VALIDATION_RUN_ID,
    target: { commit: TARGET_COMMIT, tree: TARGET_TREE },
    phase_boundary: {
      phase: "validation",
      central_validation_result_is_worker_judgment: true,
      assembler_reinterprets_worker_judgment: false,
      centralized_attack_path_analysis_performed: false,
      attack_path_receipts_planned: 0,
    },
    canonical: { path: CANONICAL_PATH, bytes: canonicalBytes.length, sha256: CANONICAL_SHA256, rows: CANONICAL_ROWS },
    batches: batchSeals,
    validation: {
      path: validationStreamPath,
      rows: validationEntries.length,
      unique_candidate_ids: new Set(validationIds).size,
      unique_canonical_lines: new Set(validationLines).size,
      disposition_counts: dispositions,
      severity_counts: severities,
      review_tier_counts: reviewTiers,
      confidence,
    },
    ledger_plan: {
      canonical_validation_receipts: ledgerPlans.length,
      canonical_attack_path_receipts: 0,
      preserves_historical_rows: true,
      receipts_are_marked_by_validation_run_id: true,
    },
  };
  const summaryBytes = jsonBytes(summary);
  const summarySha = sha256(summaryBytes);
  outputMap.set(summaryPath, summaryBytes);

  const reportPath = "artifacts/central_validation_round06/validation_report.md";
  const reportLines = [
    "# Round 06 Central Validation Assembly",
    "",
    `- Target commit: \`${TARGET_COMMIT}\``,
    `- Target tree: \`${TARGET_TREE}\``,
    `- Canonical input: \`${CANONICAL_PATH}\` (${CANONICAL_ROWS} rows, \`${CANONICAL_SHA256}\`)`,
    `- Validated rows: ${validationEntries.length} (188 unique IDs and 188 exact canonical lines)`,
    `- Dispositions: ${Object.entries(dispositions).map(([key, value]) => `${key}=${value}`).join(", ")}`,
    `- Severities: ${Object.entries(severities).map(([key, value]) => `${key}=${value}`).join(", ")}`,
    `- Planned ledger receipts: ${ledgerPlans.length} \`canonical_validation_receipt\` rows`,
    "- Planned attack-path receipts: 0",
    "- Judgment boundary: dispositions, severities, confidence, evidence, rationale, and review tier are preserved from validator outputs; this assembler does not reinterpret them.",
    "",
    "## Batch seals",
    "",
    "| Batch | Canonical lines | Validator | Rows | Validation SHA-256 | Report SHA-256 |",
    "|---|---:|---|---:|---|---|",
    ...batchSeals.map((batch) => `| ${batch.batch_id} | ${batch.canonical_lines.start}-${batch.canonical_lines.end} | ${batch.validator} | ${batch.validation.rows} | \`${batch.validation.sha256}\` | \`${batch.report.sha256}\` |`),
    "",
    "## Validator judgments",
    "",
    "| Line | Candidate | Disposition | Severity | Confidence | Review tier | Rationale |",
    "|---:|---|---|---|---:|---|---|",
    ...validationEntries.map(({ row }) => `| ${row.canonical_line} | ${markdownCell(row.candidate_id)} | ${markdownCell(row.disposition)} | ${markdownCell(row.severity)} | ${row.confidence} | ${markdownCell(row.review_tier)} | ${markdownCell(row.rationale)} |`),
    "",
    "## Source batch reports",
    "",
    ...batchReportSections.flatMap((batch) => [
      `### ${batch.batchId}`,
      "",
      `Source: \`${batch.path}\` (SHA-256 \`${batch.sha256}\`)`,
      "",
      batch.text,
      "",
    ]),
  ];
  const reportBytes = Buffer.from(reportLines.join("\n"));
  const reportSha = sha256(reportBytes);
  outputMap.set(reportPath, reportBytes);

  const builderBytes = fs.readFileSync(SCRIPT_PATH);
  const builderRelativePath = path.relative(SCAN_ROOT, SCRIPT_PATH).split(path.sep).join("/");
  const builderSha = sha256(builderBytes);
  inputSeals.set(builderRelativePath, builderSha);
  const manifestPath = "artifacts/central_validation_round06/validation_manifest.json";
  const repositoryForManifest = { ...repositoryState };
  delete repositoryForManifest.rawPorcelain;
  const manifest = {
    schema_version: "deep-security-central-validation-manifest-v1",
    event: "central_validation_assembly_manifest",
    validation_run_id: VALIDATION_RUN_ID,
    target: { commit: TARGET_COMMIT, tree: TARGET_TREE },
    safety_gate: {
      default_mode: "check-only",
      write_requires_exact_manifest_sha256: true,
      canonical_is_input_only: true,
      attack_path_receipts_planned: 0,
    },
    repository: repositoryForManifest,
    builder: { path: builderRelativePath, bytes: builderBytes.length, sha256: builderSha },
    canonical: { path: CANONICAL_PATH, bytes: canonicalBytes.length, rows: CANONICAL_ROWS, sha256: CANONICAL_SHA256 },
    batch_inputs: batchSeals,
    consolidated_outputs: {
      validation: { path: validationStreamPath, bytes: validationStreamBytes.length, rows: validationEntries.length, sha256: validationStreamSha },
      summary: { path: summaryPath, bytes: summaryBytes.length, sha256: summarySha },
      report: { path: reportPath, bytes: reportBytes.length, sha256: reportSha },
    },
    ledger_plan: ledgerPlans,
    invariants: {
      exact_rows: CANONICAL_ROWS,
      exact_unique_candidate_ids: CANONICAL_ROWS,
      exact_unique_canonical_lines: CANONICAL_ROWS,
      no_batch_gaps_or_duplicates: true,
      canonical_raw_line_hashes_verified: true,
      preappend_ledger_hashes_verified: true,
      historical_ledger_rows_preserved: true,
      central_validation_result_is_worker_judgment: true,
      canonical_validation_receipts_planned: ledgerPlans.length,
      canonical_attack_path_receipts_planned: 0,
    },
  };
  const manifestBytes = jsonBytes(manifest);
  const manifestSha = sha256(manifestBytes);
  outputMap.set(manifestPath, manifestBytes);

  const expectedOutputHashes = new Map([...outputMap.entries()].map(([relativePath, bytes]) => [relativePath, sha256(bytes)]));
  const permittedCurrentStates = new Map();
  for (const [relativePath, plannedSha] of expectedOutputHashes) {
    if (ledgerSeals.has(relativePath)) {
      const seal = ledgerSeals.get(relativePath);
      permittedCurrentStates.set(relativePath, [seal.preappendSha256, seal.plannedSha256]);
    } else {
      permittedCurrentStates.set(relativePath, [null, plannedSha]);
    }
  }
  const wouldChangeFiles = [...outputMap.entries()]
    .filter(([relativePath, bytes]) => !fs.existsSync(asAbsolute(relativePath)) || !fs.readFileSync(asAbsolute(relativePath)).equals(bytes))
    .map(([relativePath]) => relativePath);

  return {
    canonicalBytes,
    inputSeals,
    ledgerSeals,
    outputMap,
    expectedOutputHashes,
    permittedCurrentStates,
    ledgerReceiptsAlreadyPresent,
    wouldChangeFiles,
    manifestSha,
    stdout: {
      mode: null,
      target: TARGET_COMMIT,
      canonical_rows: canonical.rows.length,
      canonical_sha256: actualCanonicalSha,
      batch_rows: Object.fromEntries(batchSeals.map((batch) => [batch.batch_id, batch.validation.rows])),
      validation_rows: validationEntries.length,
      disposition_counts: dispositions,
      severity_counts: severities,
      review_tier_counts: reviewTiers,
      ledger_receipts_planned: ledgerPlans.length,
      ledger_receipts_already_present: ledgerReceiptsAlreadyPresent,
      attack_path_receipts_planned: 0,
      central_validation_stream_sha256: validationStreamSha,
      summary_sha256: summarySha,
      report_sha256: reportSha,
      planned_manifest_sha256: manifestSha,
      planned_output_files: outputMap.size,
      would_change_files: wouldChangeFiles,
      errors: [],
    },
  };
}

function verifyInputsUnchanged(plan) {
  for (const [relativePath, expectedSha] of plan.inputSeals) {
    const actualSha = sha256(readRequired(relativePath));
    assert(actualSha === expectedSha, `sealed input changed during assembly: ${relativePath}`);
  }
}

function verifyLedgersStable(plan) {
  for (const [relativePath, seal] of plan.ledgerSeals) {
    const currentSha = sha256(readRequired(relativePath));
    assert(currentSha === seal.preappendSha256 || currentSha === seal.plannedSha256,
      `canonical ledger changed during assembly: ${relativePath}`);
  }
}

function verifyRepositoryUnchanged(before) {
  const after = captureRepositoryState();
  assert(after.rawPorcelain === before.rawPorcelain, "repository porcelain status changed during assembly");
  assert(after.observed_head === before.observed_head, "repository HEAD changed during assembly");
  assert(after.observed_head_tree === before.observed_head_tree, "repository HEAD tree changed during assembly");
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const repositoryState = captureRepositoryState();
  const plan = buildPlan(repositoryState);
  plan.stdout.mode = args.mode;

  verifyInputsUnchanged(plan);
  verifyLedgersStable(plan);
  verifyRepositoryUnchanged(repositoryState);

  if (args.mode === "write") {
    assert(args.manifestSha256 === plan.manifestSha,
      `write seal mismatch: supplied ${args.manifestSha256}; current plan is ${plan.manifestSha}`);

    verifyInputsUnchanged(plan);
    verifyLedgersStable(plan);
    verifyRepositoryUnchanged(repositoryState);
    writeAtomicallyWithRollback(plan.outputMap, plan.expectedOutputHashes, plan.permittedCurrentStates, () => {
      verifyInputsUnchanged(plan);
      verifyLedgersStable(plan);
      verifyRepositoryUnchanged(repositoryState);
      assert(sha256(readRequired(CANONICAL_PATH)) === CANONICAL_SHA256, "canonical stream mutated during write");
      const postPlan = buildPlan(repositoryState);
      assert(postPlan.manifestSha === plan.manifestSha, "post-write plan seal is not idempotent");
      assert(postPlan.wouldChangeFiles.length === 0,
        `post-write check is not idempotent: ${postPlan.wouldChangeFiles.join(", ")}`);
    });
    plan.stdout.would_change_files = [];
    plan.stdout.ledger_receipts_already_present = CANONICAL_ROWS;
  }

  console.log(JSON.stringify(plan.stdout, null, 2));
}

try {
  main();
} catch (error) {
  console.error(JSON.stringify({
    mode: process.argv.includes("--write") ? "write" : "check-only",
    errors: [error.message],
  }, null, 2));
  process.exitCode = 1;
}
