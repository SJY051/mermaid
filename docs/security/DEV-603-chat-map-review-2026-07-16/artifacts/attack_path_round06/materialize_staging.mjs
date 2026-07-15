#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const SCRIPT_PATH = fileURLToPath(import.meta.url);
const OUTPUT_DIR = path.dirname(SCRIPT_PATH);
const SCAN_ROOT = path.resolve(OUTPUT_DIR, '../..');
const REAL_SCAN_ROOT = fs.realpathSync(SCAN_ROOT);
const REPO_ROOT = '/Users/asqi/Developer/mermaid';

const TARGET_COMMIT = '654f906e00e81648d1482210b6a9171747dddd75';
const TARGET_TREE = 'a14388f597c0c2a17e0dbcfc2d951a390c877214';
const TARGET_REPOSITORY_ID = 'target_sha256_3b79a0a9591bbdee6ac51053b05ea9ecc32c6b6d7bb58211be3c77de70ea2356';
const CANONICAL_PATH = 'artifacts/04_reconciliation/deduped_candidates.jsonl';
const CANONICAL_SHA256 = '274fd61156b7ae3ebeadb1c905258fbe001a5f81380891b9d2491b44652477b8';
const CENTRAL_VALIDATION_PATH = 'artifacts/central_validation_round06/validation.jsonl';
const CENTRAL_MANIFEST_PATH = 'artifacts/central_validation_round06/validation_manifest.json';
const THREAT_MODEL_PATH = 'artifacts/01_context/threat_model.md';
const APPROVED_ATTACK_BUILDER_PATH = 'artifacts/attack_path_round06/build_attack_path.mjs';
const APPROVED_ATTACK_BUILDER_SHA256 = '1421765ba8a03ca0a3f67e98c448520e23462a47781f8e04174bd0cc8397e7f6';
const MATERIALIZATION_MANIFEST_PATH = 'artifacts/attack_path_round06/materialization_manifest.json';
const CENTRAL_VALIDATION_RUN_ID = 'central-validation-round-06';
const ATTACK_PATH_RUN_ID = 'attack-path-round-06';
const MATERIALIZATION_RUN_ID = 'attack-path-staging-materialization-round-06';

const TOTAL_CANONICAL_ROWS = 188;
const EXPECTED_REPORTABLE_ROWS = 101;
const EXPECTED_NEEDS_REVIEW_ROWS = 22;
const EXPECTED_NOT_REPORTABLE_ROWS = 65;
const EXPECTED_ELIGIBLE_ROWS = 123;
const ELIGIBLE_DISPOSITIONS = ['reportable', 'needs_review'];
const VALIDATION_DISPOSITIONS = ['reportable', 'not_reportable', 'needs_review'];
const VALIDATION_SEVERITIES = ['P0', 'P1', 'P2', 'P3', 'none'];
const IMPACT_LEVELS = ['high', 'medium', 'low', 'ignore', 'unknown'];
const LIKELIHOOD_LEVELS = ['high', 'medium', 'low', 'ignore', 'unknown'];
const CALIBRATED_SEVERITIES = ['critical', 'high', 'medium', 'low', 'ignore'];
const FINAL_POLICY_DECISIONS = ['reportable', 'ignore', 'deferred'];
const FINAL_PRIORITIES = ['P0', 'P1', 'P2', 'P3', 'none'];
const HARD_SUPPRESSIONS = ['none', 'unachievable_preconditions', 'out_of_scope'];

const VALIDATION_KEYS = [
  'schema_version',
  'event',
  'candidate_id',
  'canonical_line',
  'canonical_row_sha256',
  'target',
  'disposition',
  'severity',
  'confidence',
  'source',
  'closest_control',
  'sink',
  'impact',
  'preconditions',
  'counterevidence',
  'proof_gaps',
  'evidence',
  'affected_locations',
  'fix_direction',
  'review_tier',
  'rationale',
  'canonical_ledger_path',
  'canonical_ledger_sha256',
];

const SEMANTIC_KEYS = [
  'candidate_id',
  'attack_path_steps',
  'attack_path_facts',
  'counterevidence',
  'impact_level',
  'likelihood_level',
  'critical_criteria_satisfied',
  'hard_suppression',
  'hard_suppression_rationale',
  'calibrated_severity',
  'final_policy_decision',
  'final_priority',
  'confidence',
  'proof_gaps',
  'rationale',
];

const ATTACK_FACT_KEYS = [
  'assumptions',
  'context',
  'in_scope_status',
  'in_scope_reasoning',
  'exposure',
  'exposure_evidence',
  'identity',
  'identity_evidence',
  'cross_boundary_behavior',
  'cross_boundary_evidence',
  'vector',
  'vector_evidence',
  'preconditions',
  'precondition_feasibility',
  'attacker_input_control',
  'attacker_input_evidence',
  'category',
  'mitigations_already_present',
  'auth_scope',
  'auth_scope_evidence',
  'impact_surface',
  'impact_surface_evidence',
  'target_reach',
  'target_reach_evidence',
  'secrets_references',
  'counterevidence',
  'blindspots',
  'controls',
  'confidence',
];

const EVIDENCE_KEYS = ['path', 'lines', 'claim'];

// This is the exact current build_attack_path.mjs staging schema. It has 35 keys.
const OUTPUT_KEYS = [
  'schema_version',
  'event',
  'candidate_id',
  'validation_line',
  'source_validation_path',
  'source_validation_stream_sha256',
  'source_validation_row_sha256',
  'central_validation_manifest_path',
  'central_validation_manifest_sha256',
  'threat_model_path',
  'threat_model_sha256',
  'target',
  'validation_disposition',
  'title',
  'instance_key',
  'ledger_row_id',
  'affected_locations',
  'attack_path_steps',
  'attack_path_facts',
  'counterevidence',
  'impact_level',
  'likelihood_level',
  'critical_criteria_satisfied',
  'hard_suppression',
  'hard_suppression_rationale',
  'calibrated_severity',
  'final_policy_decision',
  'final_priority',
  'confidence',
  'proof_gaps',
  'rationale',
  'report_path',
  'report_sha256',
  'canonical_ledger_path',
  'canonical_ledger_sha256',
];

// Final accepted cross-audit byte freeze. Any drift requires a new review and seal.
const BATCHES = [
  {
    id: 'batch-01', start: 1, end: 38, expectedEligibleRows: 33,
    draftSha256: '2b417d775da3a54a46740289357c70281bc373d06147db60283c45cf62be672c',
    reportsBundleSha256: '1d63478b5c738ef7d240d8c524f2127dc5b733319b92633297f4d043eda443aa',
    sourceReportsDir: 'artifacts/attack_path_round06/batch-01/reports',
  },
  {
    id: 'batch-02', start: 39, end: 76, expectedEligibleRows: 10,
    draftSha256: 'b19fae12e9b2260900f6b09b95263b54a217acbd3c51c081d21b4471cf7c4381',
    reportsBundleSha256: 'c7e8b4c0826cbb17c1502c40a61444c7be07c9dea533f0e655f8048b505c4f5f',
    sourceReportsDir: 'artifacts/attack_path_round06/batch-02/reports',
  },
  {
    id: 'batch-03', start: 77, end: 113, expectedEligibleRows: 24,
    draftSha256: '6b0edebc34ad40cc470b142f9548af7b5ed619b3f40e061631dc79619f7bfaa5',
    reportsBundleSha256: '0c31d3f40b9b9d01d8480ce17f4eae0a1ca0c278b59e71f56c34f8fd2aa806fb',
    sourceReportsDir: 'artifacts/attack_path_round06/batch-03/reports',
  },
  {
    id: 'batch-04', start: 114, end: 151, expectedEligibleRows: 23,
    draftSha256: '78d51d018f6049efa66af3aa97e5ab081d6ce3edf8408c5c0a277465ac57cf5e',
    reportsBundleSha256: '4009560d5e7f7bf16845506970163b7d1a7a2ac29c0e3a43b7c0ba5e706f778d',
    sourceReportsDir: 'artifacts/attack_path_round06/batch-04/reports',
  },
  {
    id: 'batch-05', start: 152, end: 188, expectedEligibleRows: 33,
    draftSha256: '6912468eb296915c9a034250ecde308a3afa75464863558aa0b47454ae57bc48',
    reportsBundleSha256: '0d5f3197d4fba327535a36298bdb3964fdaeee900f5c19b5f7bdedd99f2fddc4',
    sourceReportsDir: 'artifacts/attack_path_round06/batch-05/reports',
  },
].map(function (batch) {
  return {
    ...batch,
    draftPath: 'artifacts/attack_path_round06/drafts/' + batch.id + '.jsonl',
    outputPath: 'artifacts/attack_path_round06/' + batch.id + '/attack_path.jsonl',
    outputReportsDir: 'artifacts/attack_path_round06/' + batch.id + '/reports',
  };
});

function fail(message) {
  throw new Error(message);
}

function assert(condition, message) {
  if (!condition) fail(message);
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function asAbsolute(relativePath) {
  assert(typeof relativePath === 'string' && relativePath.length > 0, 'path must be a nonempty string');
  const absolutePath = path.resolve(SCAN_ROOT, relativePath);
  const relative = path.relative(SCAN_ROOT, absolutePath);
  assert(relative !== '..' && !relative.startsWith('..' + path.sep) && !path.isAbsolute(relative),
    'path escapes scan root: ' + relativePath);
  return absolutePath;
}

function assertRealInside(realPath, label) {
  const relative = path.relative(REAL_SCAN_ROOT, realPath);
  assert(relative !== '..' && !relative.startsWith('..' + path.sep) && !path.isAbsolute(relative),
    label + ' resolves outside scan root: ' + realPath);
}

function assertSafeDirectoryAbsolute(absolutePath, label) {
  const stat = fs.lstatSync(absolutePath);
  assert(stat.isDirectory() && !stat.isSymbolicLink(), label + ' must be a real directory');
  const realPath = fs.realpathSync(absolutePath);
  assertRealInside(realPath, label);
  assert(realPath === path.resolve(absolutePath), label + ' contains a symlinked path component');
}

function assertSafeRegularFileAbsolute(absolutePath, label) {
  const stat = fs.lstatSync(absolutePath);
  assert(stat.isFile() && !stat.isSymbolicLink(), label + ' must be a regular non-symlink file');
  const realPath = fs.realpathSync(absolutePath);
  assertRealInside(realPath, label);
  assert(realPath === path.resolve(absolutePath), label + ' contains a symlinked path component');
}

function inspectOutputPath(relativePath) {
  const absolutePath = asAbsolute(relativePath);
  const parts = path.relative(SCAN_ROOT, absolutePath).split(path.sep);
  let current = SCAN_ROOT;
  parts.forEach(function (part, index) {
    if (!fs.existsSync(current)) return;
    current = path.join(current, part);
    if (!fs.existsSync(current)) return;
    const stat = fs.lstatSync(current);
    assert(!stat.isSymbolicLink(), relativePath + ' contains a symlinked path component');
    if (index === parts.length - 1) {
      assert(stat.isFile(), relativePath + ' exists but is not a regular file');
    } else {
      assert(stat.isDirectory(), relativePath + ' has a non-directory path component');
    }
    assertRealInside(fs.realpathSync(current), relativePath);
  });
  return absolutePath;
}

function readRequired(relativePath) {
  const absolutePath = asAbsolute(relativePath);
  assert(fs.existsSync(absolutePath), 'missing required input: ' + relativePath);
  assertSafeRegularFileAbsolute(absolutePath, relativePath);
  const bytes = fs.readFileSync(absolutePath);
  assert(bytes.length > 0, 'required input is empty: ' + relativePath);
  return bytes;
}

function readOutputState(relativePath) {
  const absolutePath = inspectOutputPath(relativePath);
  if (!fs.existsSync(absolutePath)) return null;
  return {
    bytes: fs.readFileSync(absolutePath),
    mode: fs.statSync(absolutePath).mode & 0o777,
  };
}

function parseStrictJsonl(relativePath, bytes) {
  assert(bytes.at(-1) === 0x0a, relativePath + ' must end with LF');
  assert(!bytes.includes(0x0d), relativePath + ' must not contain CR bytes');
  const rawLineBytes = [];
  let start = 0;
  for (let index = 0; index < bytes.length; index += 1) {
    if (bytes[index] === 0x0a) {
      rawLineBytes.push(bytes.subarray(start, index));
      start = index + 1;
    }
  }
  const rawLines = rawLineBytes.map(function (lineBytes, index) {
    assert(lineBytes.length > 0, relativePath + ':' + (index + 1) + ' is a blank JSONL row');
    const line = lineBytes.toString('utf8');
    assert(Buffer.from(line).equals(lineBytes), relativePath + ':' + (index + 1) + ' is not valid UTF-8');
    return line;
  });
  const rows = rawLines.map(function (line, index) {
    let row;
    try {
      row = JSON.parse(line);
    } catch (error) {
      fail(relativePath + ':' + (index + 1) + ' is invalid JSON: ' + error.message);
    }
    assert(JSON.stringify(row) === line,
      relativePath + ':' + (index + 1) + ' must be canonical compact JSON with no duplicate object members');
    return row;
  });
  return { rawLineBytes, rawLines, rows };
}

function parsePrettyJson(relativePath, bytes) {
  assert(bytes.at(-1) === 0x0a, relativePath + ' must end with LF');
  assert(!bytes.includes(0x0d), relativePath + ' must not contain CR bytes');
  const text = bytes.toString('utf8');
  assert(Buffer.from(text).equals(bytes), relativePath + ' is not valid UTF-8');
  let value;
  try {
    value = JSON.parse(text);
  } catch (error) {
    fail(relativePath + ' is invalid JSON: ' + error.message);
  }
  assert(JSON.stringify(value, null, 2) + '\n' === text,
    relativePath + ' must be canonical pretty JSON with no duplicate object members');
  return value;
}

function exactKeys(value, keys, label) {
  assert(value !== null && typeof value === 'object' && !Array.isArray(value), label + ' must be an object');
  const actual = Object.keys(value);
  assert(actual.length === keys.length && actual.every(function (key, index) { return key === keys[index]; }),
    label + ' keys/order mismatch: expected ' + keys.join(',') + '; got ' + actual.join(','));
}

function nonemptyString(value, label) {
  assert(typeof value === 'string' && value.trim().length > 0, label + ' must be a nonempty string');
}

function stringArray(value, label) {
  assert(Array.isArray(value), label + ' must be an array');
  value.forEach(function (item, index) {
    nonemptyString(item, label + '[' + index + ']');
  });
}

function finiteConfidence(value, label) {
  assert(typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 1,
    label + ' must be a finite number from 0 through 1');
}

function validateEvidence(value, label) {
  assert(Array.isArray(value), label + ' must be an array');
  value.forEach(function (item, index) {
    exactKeys(item, EVIDENCE_KEYS, label + '[' + index + ']');
    EVIDENCE_KEYS.forEach(function (key) {
      nonemptyString(item[key], label + '[' + index + '].' + key);
    });
  });
}

function validateAffectedLocations(value, label) {
  assert(Array.isArray(value), label + ' must be an array');
  value.forEach(function (item, index) {
    assert(item !== null && typeof item === 'object' && !Array.isArray(item),
      label + '[' + index + '] must be an object');
    nonemptyString(item.path, label + '[' + index + '].path');
    nonemptyString(item.lines, label + '[' + index + '].lines');
    if (Object.hasOwn(item, 'label')) assert(typeof item.label === 'string', label + '[' + index + '].label must be a string');
    if (Object.hasOwn(item, 'detail')) assert(typeof item.detail === 'string', label + '[' + index + '].detail must be a string');
  });
}

function deepEqual(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}

function containsExcludedEvidencePath(value) {
  return typeof value === 'string'
    && /(^|[^A-Za-z0-9._-])(quarantine|preterminal_validation_drafts)([\\/]|$)/i.test(value);
}

function assertNoExcludedEvidenceReferences(value, label) {
  if (typeof value === 'string') {
    assert(!containsExcludedEvidencePath(value),
      label + ' contains an excluded quarantine or preterminal-validation-drafts path reference');
    return;
  }
  if (Array.isArray(value)) {
    value.forEach(function (item, index) {
      assertNoExcludedEvidenceReferences(item, label + '[' + index + ']');
    });
    return;
  }
  if (value !== null && typeof value === 'object') {
    Object.entries(value).forEach(function (entry) {
      assertNoExcludedEvidenceReferences(entry[1], label + '.' + entry[0]);
    });
  }
}

function countsBy(values) {
  const counts = {};
  values.forEach(function (value) {
    counts[value] = (counts[value] || 0) + 1;
  });
  return counts;
}

function git(args) {
  return execFileSync('git', ['-C', REPO_ROOT, ...args], {
    encoding: 'utf8',
    env: { ...process.env, GIT_OPTIONAL_LOCKS: '0' },
    stdio: ['ignore', 'pipe', 'pipe'],
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
  assert(gitQuiet(['diff', '--quiet']), 'repository has tracked unstaged changes');
  assert(gitQuiet(['diff', '--cached', '--quiet']), 'repository has staged changes');
  assert(git(['cat-file', '-t', TARGET_COMMIT]).trim() === 'commit', 'target object is not a commit');
  assert(git(['rev-parse', TARGET_COMMIT + '^{tree}']).trim() === TARGET_TREE, 'target tree mismatch');
  const porcelain = git(['status', '--porcelain=v1', '--untracked-files=all']);
  return {
    path: REPO_ROOT,
    observed_head: git(['rev-parse', 'HEAD']).trim(),
    observed_head_tree: git(['rev-parse', 'HEAD^{tree}']).trim(),
    target_commit_object_verified: true,
    target_tree_verified: true,
    tracked_clean: true,
    staged_clean: true,
    porcelain_sha256: sha256(Buffer.from(porcelain)),
    porcelain_lines: porcelain.length === 0 ? [] : porcelain.trimEnd().split('\n'),
    rawPorcelain: porcelain,
  };
}

function verifyRepositoryUnchanged(before) {
  const after = captureRepositoryState();
  assert(after.rawPorcelain === before.rawPorcelain, 'repository porcelain changed during materialization');
  assert(after.observed_head === before.observed_head, 'repository HEAD changed during materialization');
  assert(after.observed_head_tree === before.observed_head_tree, 'repository HEAD tree changed during materialization');
}

function parseArgs(argv) {
  let mode = 'check-only';
  let explicitMode = false;
  let manifestSha256 = null;
  argv.forEach(function (arg) {
    if (arg === '--check-only' || arg === '--write') {
      const selected = arg.slice(2);
      assert(!explicitMode || mode === selected, '--check-only and --write are mutually exclusive');
      mode = selected;
      explicitMode = true;
    } else if (arg.startsWith('--manifest-sha256=')) {
      assert(manifestSha256 === null, '--manifest-sha256 may be supplied only once');
      manifestSha256 = arg.slice('--manifest-sha256='.length);
      assert(/^[a-f0-9]{64}$/.test(manifestSha256), '--manifest-sha256 must be a lowercase SHA-256');
    } else {
      fail('unknown argument: ' + arg);
    }
  });
  if (mode === 'write') {
    assert(manifestSha256 !== null, '--write requires --manifest-sha256=<check-only planned_manifest_sha256>');
  } else {
    assert(manifestSha256 === null, '--manifest-sha256 is valid only with --write');
  }
  return { mode, manifestSha256 };
}

function jsonBytes(value) {
  return Buffer.from(JSON.stringify(value, null, 2) + '\n');
}

function validateCentralValidationRow(row, label) {
  exactKeys(row, VALIDATION_KEYS, label);
  assert(row.schema_version === 'deep-security-central-validation-v1', label + '.schema_version mismatch');
  assert(row.event === 'centralized_validation_receipt', label + '.event mismatch');
  nonemptyString(row.candidate_id, label + '.candidate_id');
  assert(Number.isInteger(row.canonical_line), label + '.canonical_line must be an integer');
  assert(/^[a-f0-9]{64}$/.test(row.canonical_row_sha256), label + '.canonical_row_sha256 must be SHA-256');
  assert(row.target === TARGET_COMMIT, label + '.target mismatch');
  assert(VALIDATION_DISPOSITIONS.includes(row.disposition), label + '.disposition is outside the enum');
  assert(VALIDATION_SEVERITIES.includes(row.severity), label + '.severity is outside the enum');
  finiteConfidence(row.confidence, label + '.confidence');
  validateAffectedLocations(row.affected_locations, label + '.affected_locations');
  assertNoExcludedEvidenceReferences(row.affected_locations, label + '.affected_locations');
  const ledgerPath = 'artifacts/05_findings/' + row.candidate_id + '/candidate_ledger.jsonl';
  assert(row.canonical_ledger_path === ledgerPath, label + '.canonical_ledger_path mismatch');
  assert(/^[a-f0-9]{64}$/.test(row.canonical_ledger_sha256), label + '.canonical_ledger_sha256 must be SHA-256');
}

function validateAttackFacts(value, label) {
  exactKeys(value, ATTACK_FACT_KEYS, label);
  stringArray(value.assumptions, label + '.assumptions');
  nonemptyString(value.context, label + '.context');
  assert(['in_scope', 'out_of_scope', 'unknown'].includes(value.in_scope_status),
    label + '.in_scope_status is outside the enum');
  nonemptyString(value.in_scope_reasoning, label + '.in_scope_reasoning');
  assert(['public', 'internal', 'admin_only', 'localhost', 'none', 'unknown'].includes(value.exposure),
    label + '.exposure is outside the enum');
  validateEvidence(value.exposure_evidence, label + '.exposure_evidence');
  nonemptyString(value.identity, label + '.identity');
  validateEvidence(value.identity_evidence, label + '.identity_evidence');
  assert(['verified', 'plausible', 'not_verified', 'none', 'unknown'].includes(value.cross_boundary_behavior),
    label + '.cross_boundary_behavior is outside the enum');
  validateEvidence(value.cross_boundary_evidence, label + '.cross_boundary_evidence');
  assert(['remote', 'local_network', 'localhost', 'none', 'unknown'].includes(value.vector),
    label + '.vector is outside the enum');
  validateEvidence(value.vector_evidence, label + '.vector_evidence');
  stringArray(value.preconditions, label + '.preconditions');
  assert(['plausible', 'unlikely', 'unachievable', 'unknown'].includes(value.precondition_feasibility),
    label + '.precondition_feasibility is outside the enum');
  assert(['yes', 'plausible', 'no', 'unknown'].includes(value.attacker_input_control),
    label + '.attacker_input_control is outside the enum');
  validateEvidence(value.attacker_input_evidence, label + '.attacker_input_evidence');
  nonemptyString(value.category, label + '.category');
  stringArray(value.mitigations_already_present, label + '.mitigations_already_present');
  assert(['public', 'internal_only', 'admin_only', 'unknown'].includes(value.auth_scope),
    label + '.auth_scope is outside the enum');
  validateEvidence(value.auth_scope_evidence, label + '.auth_scope_evidence');
  assert(['build', 'runtime', 'data', 'identity', 'network', 'other'].includes(value.impact_surface),
    label + '.impact_surface is outside the enum');
  validateEvidence(value.impact_surface_evidence, label + '.impact_surface_evidence');
  assert(['single_service', 'base_image', 'fleet', 'unknown'].includes(value.target_reach),
    label + '.target_reach is outside the enum');
  validateEvidence(value.target_reach_evidence, label + '.target_reach_evidence');
  stringArray(value.secrets_references, label + '.secrets_references');
  stringArray(value.counterevidence, label + '.counterevidence');
  assert(value.counterevidence.length > 0,
    label + '.counterevidence must identify the strongest conflicting repository evidence');
  stringArray(value.blindspots, label + '.blindspots');
  stringArray(value.controls, label + '.controls');
  finiteConfidence(value.confidence, label + '.confidence');
}

function expectedSeverity(impact, likelihood, criticalCriteriaSatisfied) {
  if (impact === 'high') {
    if (likelihood === 'high') return criticalCriteriaSatisfied ? 'critical' : 'high';
    if (likelihood === 'medium') return 'medium';
    if (likelihood === 'low' || likelihood === 'ignore') return 'ignore';
    return 'medium';
  }
  if (impact === 'medium') {
    if (likelihood === 'high') return 'medium';
    if (likelihood === 'medium') return 'low';
    if (likelihood === 'low' || likelihood === 'ignore') return 'ignore';
    return 'low';
  }
  if (impact === 'low' || impact === 'ignore') return 'ignore';
  if (likelihood === 'high') return 'medium';
  if (likelihood === 'medium') return 'low';
  if (likelihood === 'low' || likelihood === 'ignore') return 'ignore';
  return 'low';
}

function expectedPriority(severity) {
  return {
    critical: 'P0',
    high: 'P1',
    medium: 'P2',
    low: 'P3',
    ignore: 'none',
  }[severity];
}

function validateSemanticPolicy(row, label) {
  assert(IMPACT_LEVELS.includes(row.impact_level), label + '.impact_level is outside the enum');
  assert(LIKELIHOOD_LEVELS.includes(row.likelihood_level), label + '.likelihood_level is outside the enum');
  assert(typeof row.critical_criteria_satisfied === 'boolean', label + '.critical_criteria_satisfied must be boolean');
  if (row.critical_criteria_satisfied) {
    assert(row.impact_level === 'high' && row.likelihood_level === 'high',
      label + '.critical_criteria_satisfied may be true only for high/high');
  }
  assert(HARD_SUPPRESSIONS.includes(row.hard_suppression), label + '.hard_suppression is outside the approved enum');
  nonemptyString(row.hard_suppression_rationale, label + '.hard_suppression_rationale');
  assert(CALIBRATED_SEVERITIES.includes(row.calibrated_severity),
    label + '.calibrated_severity is outside the enum');
  assert(FINAL_POLICY_DECISIONS.includes(row.final_policy_decision),
    label + '.final_policy_decision is outside the enum');
  assert(FINAL_PRIORITIES.includes(row.final_priority), label + '.final_priority is outside the enum');
  const expectedSuppression = row.attack_path_facts.in_scope_status === 'out_of_scope'
    ? 'out_of_scope'
    : row.attack_path_facts.precondition_feasibility === 'unachievable'
      ? 'unachievable_preconditions'
      : 'none';
  assert(row.hard_suppression === expectedSuppression,
    label + '.hard_suppression must equal structured-fact result ' + expectedSuppression);
  if (row.hard_suppression !== 'none') {
    assert(row.calibrated_severity === 'ignore'
      && row.final_policy_decision === 'ignore' && row.final_priority === 'none',
    label + ' hard suppression requires ignore/ignore/none');
    return;
  }
  const expected = expectedSeverity(row.impact_level, row.likelihood_level, row.critical_criteria_satisfied);
  assert(row.calibrated_severity === expected,
    label + '.calibrated_severity must mechanically equal ' + expected);
  assert(row.final_priority === expectedPriority(expected),
    label + '.final_priority must mechanically equal ' + expectedPriority(expected));
  if (expected === 'ignore') {
    assert(row.final_policy_decision === 'ignore', label + ' ignored severity requires final_policy_decision=ignore');
  } else {
    assert(['reportable', 'deferred'].includes(row.final_policy_decision),
      label + ' non-ignored severity requires reportable or deferred policy');
  }
}

function validateSemanticDraft(row, label) {
  exactKeys(row, SEMANTIC_KEYS, label);
  nonemptyString(row.candidate_id, label + '.candidate_id');
  stringArray(row.attack_path_steps, label + '.attack_path_steps');
  assert(row.attack_path_steps.length > 0, label + '.attack_path_steps must not be empty');
  validateAttackFacts(row.attack_path_facts, label + '.attack_path_facts');
  stringArray(row.counterevidence, label + '.counterevidence');
  assert(deepEqual(row.counterevidence, row.attack_path_facts.counterevidence),
    label + '.counterevidence must equal attack_path_facts.counterevidence');
  validateSemanticPolicy(row, label);
  finiteConfidence(row.confidence, label + '.confidence');
  assert(row.confidence === row.attack_path_facts.confidence,
    label + '.confidence must equal attack_path_facts.confidence');
  stringArray(row.proof_gaps, label + '.proof_gaps');
  nonemptyString(row.rationale, label + '.rationale');
  assertNoExcludedEvidenceReferences(row, label);
}

function semanticProjection(row) {
  return Object.fromEntries(SEMANTIC_KEYS.map(function (key) { return [key, row[key]]; }));
}

function validateCentralManifest(manifest, validationBytes) {
  assert(manifest.schema_version === 'deep-security-central-validation-manifest-v1',
    CENTRAL_MANIFEST_PATH + '.schema_version mismatch');
  assert(manifest.event === 'central_validation_assembly_manifest', CENTRAL_MANIFEST_PATH + '.event mismatch');
  assert(manifest.validation_run_id === CENTRAL_VALIDATION_RUN_ID,
    CENTRAL_MANIFEST_PATH + '.validation_run_id mismatch');
  assert(manifest.target && manifest.target.commit === TARGET_COMMIT && manifest.target.tree === TARGET_TREE,
    CENTRAL_MANIFEST_PATH + '.target mismatch');
  assert(manifest.canonical && manifest.canonical.path === CANONICAL_PATH
    && manifest.canonical.sha256 === CANONICAL_SHA256 && manifest.canonical.rows === TOTAL_CANONICAL_ROWS,
  CENTRAL_MANIFEST_PATH + '.canonical seal mismatch');
  assert(manifest.consolidated_outputs && manifest.consolidated_outputs.validation
    && manifest.consolidated_outputs.validation.path === CENTRAL_VALIDATION_PATH
    && manifest.consolidated_outputs.validation.sha256 === sha256(validationBytes)
    && manifest.consolidated_outputs.validation.rows === TOTAL_CANONICAL_ROWS,
  CENTRAL_MANIFEST_PATH + '.validation output seal mismatch');
  assert(manifest.invariants && manifest.invariants.exact_rows === TOTAL_CANONICAL_ROWS
    && manifest.invariants.canonical_validation_receipts_planned === TOTAL_CANONICAL_ROWS
    && manifest.invariants.canonical_attack_path_receipts_planned === 0,
  CENTRAL_MANIFEST_PATH + '.invariants mismatch');
  assert(Array.isArray(manifest.ledger_plan) && manifest.ledger_plan.length === TOTAL_CANONICAL_ROWS,
    CENTRAL_MANIFEST_PATH + '.ledger_plan must contain 188 rows');
}

function inspectCentralLedgers(validationEntries, centralManifest, inputSeals) {
  const manifestPlans = new Map();
  centralManifest.ledger_plan.forEach(function (plan, index) {
    nonemptyString(plan.candidate_id, CENTRAL_MANIFEST_PATH + '.ledger_plan[' + index + '].candidate_id');
    assert(!manifestPlans.has(plan.candidate_id), CENTRAL_MANIFEST_PATH + ' has duplicate ledger plan IDs');
    manifestPlans.set(plan.candidate_id, plan);
  });
  const ledgerInfo = new Map();
  validationEntries.forEach(function (entry) {
    const row = entry.row;
    const ledgerPath = row.canonical_ledger_path;
    const currentBytes = readRequired(ledgerPath);
    const parsed = parseStrictJsonl(ledgerPath, currentBytes);
    const centralIndexes = [];
    const attackIndexes = [];
    parsed.rows.forEach(function (ledgerRow, index) {
      if (ledgerRow.validation_run_id === CENTRAL_VALIDATION_RUN_ID) centralIndexes.push(index);
      if (ledgerRow.attack_path_run_id === ATTACK_PATH_RUN_ID) attackIndexes.push(index);
      if (Object.hasOwn(ledgerRow, 'canonical_candidate_id')) {
        assert(ledgerRow.canonical_candidate_id === row.candidate_id,
          ledgerPath + ':' + (index + 1) + '.canonical_candidate_id mismatch');
      }
    });
    assert(centralIndexes.length === 1, ledgerPath + ' must contain exactly one central validation receipt');
    assert(attackIndexes.length === 0, ledgerPath + ' must not contain an attack-path receipt before materialization');
    const centralIndex = centralIndexes[0];
    assert(centralIndex === parsed.rows.length - 1, ledgerPath + ' central validation receipt must be the final row');
    assert(centralIndex > 0, ledgerPath + ' has no historical prefix before central validation');
    const preCentralBytes = Buffer.from(parsed.rawLines.slice(0, centralIndex).join('\n') + '\n');
    const preCentralSha = sha256(preCentralBytes);
    const centralReceipt = parsed.rows[centralIndex];
    const manifestPlan = manifestPlans.get(row.candidate_id);
    assert(manifestPlan && manifestPlan.path === ledgerPath,
      CENTRAL_MANIFEST_PATH + ' has no matching ledger plan for ' + row.candidate_id);
    assert(preCentralSha === row.canonical_ledger_sha256,
      ledgerPath + ' historical prefix does not match source validation canonical_ledger_sha256');
    assert(centralReceipt.canonical_ledger_preappend_sha256 === preCentralSha,
      ledgerPath + ' central receipt preappend hash mismatch');
    assert(manifestPlan.preappend_sha256 === preCentralSha,
      ledgerPath + ' central manifest preappend hash mismatch');
    assert(centralReceipt.schema_version === 'deep-security-scan-candidate-ledger-v1'
      && centralReceipt.event === 'canonical_validation_receipt'
      && centralReceipt.phase === 'validation'
      && centralReceipt.validation_run_id === CENTRAL_VALIDATION_RUN_ID
      && centralReceipt.canonical_candidate_id === row.candidate_id,
    ledgerPath + ' central validation receipt identity mismatch');
    assert(centralReceipt.target === TARGET_COMMIT && centralReceipt.target_tree === TARGET_TREE,
      ledgerPath + ' central receipt target mismatch');
    assert(centralReceipt.centralized_validation_performed === true
      && centralReceipt.centralized_attack_path_analysis_performed === false,
    ledgerPath + ' central receipt phase flags mismatch');
    assert(centralReceipt.canonical_line === row.canonical_line
      && centralReceipt.canonical_row_sha256 === row.canonical_row_sha256
      && centralReceipt.canonical_ledger_path === ledgerPath,
    ledgerPath + ' central receipt canonical binding mismatch');
    assert(centralReceipt.validation_disposition === row.disposition
      && centralReceipt.severity === row.severity
      && centralReceipt.confidence === row.confidence,
    ledgerPath + ' central receipt validation result mismatch');
    assert(centralReceipt.source_validation_artifact
      && centralReceipt.source_validation_artifact.canonical_line === entry.line
      && centralReceipt.source_validation_artifact.row_sha256 === entry.rawSha256,
    ledgerPath + ' central receipt source validation backlink mismatch');
    assert(deepEqual(centralReceipt.source_validation, row),
      ledgerPath + ' central receipt does not embed the exact source validation row');
    const currentSha = sha256(currentBytes);
    assert(manifestPlan.receipt_row_sha256 === sha256(parsed.rawLineBytes[centralIndex]),
      ledgerPath + ' central receipt row hash mismatch');
    assert(manifestPlan.planned_sha256 === currentSha,
      ledgerPath + ' current post-central hash does not match central manifest');
    inputSeals.set(ledgerPath, currentSha);
    ledgerInfo.set(row.candidate_id, { path: ledgerPath, sha256: currentSha });
  });
  assert(ledgerInfo.size === TOTAL_CANONICAL_ROWS, 'post-central ledgers do not cover all 188 candidates');
  return ledgerInfo;
}

function validateReportBytes(relativePath, bytes) {
  assert(bytes.at(-1) === 0x0a, relativePath + ' must end with LF');
  assert(!bytes.includes(0x0d), relativePath + ' must not contain CR bytes');
  const text = bytes.toString('utf8');
  assert(Buffer.from(text).equals(bytes), relativePath + ' is not valid UTF-8');
  assert(!containsExcludedEvidencePath(text), relativePath + ' contains an excluded evidence path reference');
}

function reportInventory(relativeDir, expectedNames, allowMissing) {
  const absoluteDir = asAbsolute(relativeDir);
  if (!fs.existsSync(absoluteDir)) {
    assert(allowMissing, 'missing report directory: ' + relativeDir);
    return null;
  }
  assertSafeDirectoryAbsolute(absoluteDir, relativeDir);
  const entries = fs.readdirSync(absoluteDir, { withFileTypes: true });
  entries.forEach(function (entry) {
    assert(entry.isFile() && !entry.isSymbolicLink(), relativeDir + '/' + entry.name + ' must be a regular file');
  });
  const actualNames = entries.map(function (entry) { return entry.name; }).sort();
  const sortedExpected = [...expectedNames].sort();
  assert(deepEqual(actualNames, sortedExpected),
    relativeDir + ' inventory mismatch: expected ' + sortedExpected.join(',') + '; got ' + actualNames.join(','));
  return actualNames;
}

function validateBatchOutputDirectory(batch) {
  const relativeDir = path.dirname(batch.outputPath).split(path.sep).join('/');
  const absoluteDir = asAbsolute(relativeDir);
  if (!fs.existsSync(absoluteDir)) return;
  assertSafeDirectoryAbsolute(absoluteDir, relativeDir);
  const entries = fs.readdirSync(absoluteDir, { withFileTypes: true });
  entries.forEach(function (entry) {
    assert(['reports', 'attack_path.jsonl'].includes(entry.name),
      relativeDir + ' contains unexpected entry: ' + entry.name);
    assert(!entry.isSymbolicLink(), relativeDir + '/' + entry.name + ' must not be a symlink');
    if (entry.name === 'reports') {
      assert(entry.isDirectory(), relativeDir + '/reports must be a directory');
    } else {
      assert(entry.isFile(), relativeDir + '/attack_path.jsonl must be a regular file');
    }
  });
}

function reportBundleBytes(entries) {
  return Buffer.from(entries.map(function (entry) { return JSON.stringify(entry); }).join('\n') + '\n');
}

function collectMissingOutputDirectories(outputMap) {
  const directories = new Set();
  for (const relativePath of outputMap.keys()) {
    let parent = path.dirname(asAbsolute(relativePath));
    while (!fs.existsSync(parent)) {
      assertRealInside(path.resolve(parent), 'planned output directory');
      directories.add(parent);
      parent = path.dirname(parent);
    }
    assertSafeDirectoryAbsolute(parent, 'existing output ancestor');
  }
  return [...directories].sort(function (left, right) {
    return left.split(path.sep).length - right.split(path.sep).length;
  });
}

function assertNoOrphanTransactionFiles(outputMap) {
  const parents = new Set();
  for (const relativePath of outputMap.keys()) {
    const parent = path.dirname(asAbsolute(relativePath));
    if (fs.existsSync(parent)) parents.add(parent);
  }
  const transactionName = /^\..+\.\d+\.[0-9a-f-]+\.(tmp|restore)$/i;
  parents.forEach(function (parent) {
    assertSafeDirectoryAbsolute(parent, 'transaction output parent');
    const orphans = fs.readdirSync(parent).filter(function (name) { return transactionName.test(name); });
    assert(orphans.length === 0,
      'orphan transaction files require manual review: ' + orphans.map(function (name) {
        return path.join(parent, name);
      }).join(','));
  });
}

function writeTransaction(outputMap, expectedHashes, postWriteCheck) {
  const changedEntries = [];
  for (const [relativePath, plannedBytes] of outputMap) {
    const state = readOutputState(relativePath);
    if (state === null || !state.bytes.equals(plannedBytes)) changedEntries.push([relativePath, plannedBytes]);
  }
  if (changedEntries.length === 0) {
    postWriteCheck();
    return;
  }
  const manifestIndex = changedEntries.findIndex(function (entry) {
    return entry[0] === MATERIALIZATION_MANIFEST_PATH;
  });
  if (manifestIndex >= 0) {
    const manifestEntry = changedEntries.splice(manifestIndex, 1)[0];
    changedEntries.push(manifestEntry);
  }

  changedEntries.forEach(function (entry) {
    const state = readOutputState(entry[0]);
    assert(state === null, 'refusing to overwrite uncommitted materialization output: ' + entry[0]);
  });

  const createdDirectories = [];
  const stagedTemps = new Map();
  const committedPaths = [];
  try {
    const outputSubset = new Map(changedEntries);
    collectMissingOutputDirectories(outputSubset).forEach(function (directory) {
      fs.mkdirSync(directory, { mode: 0o755 });
      createdDirectories.push(directory);
      assertSafeDirectoryAbsolute(directory, 'created output directory');
    });
    changedEntries.forEach(function (entry) {
      const relativePath = entry[0];
      const plannedBytes = entry[1];
      const absolutePath = inspectOutputPath(relativePath);
      const parent = path.dirname(absolutePath);
      assertSafeDirectoryAbsolute(parent, 'output parent for ' + relativePath);
      const tempPath = path.join(parent,
        '.' + path.basename(absolutePath) + '.' + process.pid + '.' + randomUUID() + '.tmp');
      stagedTemps.set(relativePath, tempPath);
      fs.writeFileSync(tempPath, plannedBytes, { flag: 'wx', mode: 0o644 });
      assertSafeRegularFileAbsolute(tempPath, 'staged temporary output');
      const descriptor = fs.openSync(tempPath, 'r');
      try {
        fs.fsyncSync(descriptor);
      } finally {
        fs.closeSync(descriptor);
      }
    });
    changedEntries.forEach(function (entry) {
      const relativePath = entry[0];
      const absolutePath = inspectOutputPath(relativePath);
      assert(!fs.existsSync(absolutePath), 'output appeared concurrently before rename: ' + relativePath);
      fs.renameSync(stagedTemps.get(relativePath), absolutePath);
      stagedTemps.delete(relativePath);
      committedPaths.push(relativePath);
    });
    for (const [relativePath, plannedSha] of expectedHashes) {
      assert(sha256(readRequired(relativePath)) === plannedSha,
        'post-write hash mismatch for ' + relativePath);
    }
    postWriteCheck();
  } catch (error) {
    const rollbackFailures = [];
    stagedTemps.forEach(function (tempPath) {
      try {
        if (fs.existsSync(tempPath)) fs.unlinkSync(tempPath);
      } catch (cleanupError) {
        rollbackFailures.push('remove ' + tempPath + ': ' + cleanupError.message);
      }
    });
    [...committedPaths].reverse().forEach(function (relativePath) {
      try {
        const absolutePath = asAbsolute(relativePath);
        if (fs.existsSync(absolutePath)) fs.unlinkSync(absolutePath);
      } catch (cleanupError) {
        rollbackFailures.push('remove ' + relativePath + ': ' + cleanupError.message);
      }
    });
    [...createdDirectories].reverse().forEach(function (directory) {
      try {
        fs.rmdirSync(directory);
      } catch (cleanupError) {
        rollbackFailures.push('remove directory ' + directory + ': ' + cleanupError.message);
      }
    });
    committedPaths.forEach(function (relativePath) {
      try {
        assert(!fs.existsSync(asAbsolute(relativePath)), 'rollback left output: ' + relativePath);
      } catch (verifyError) {
        rollbackFailures.push('verify ' + relativePath + ': ' + verifyError.message);
      }
    });
    if (rollbackFailures.length > 0) {
      fail(error.message + '; rollback failures: ' + rollbackFailures.join('; '));
    }
    throw error;
  }
}

function buildPlan(repositoryState) {
  assert(OUTPUT_KEYS.length === 35, 'builder staging schema count drifted from 35 keys');
  assert(BATCHES.reduce(function (total, batch) { return total + batch.expectedEligibleRows; }, 0)
    === EXPECTED_ELIGIBLE_ROWS, 'configured batch totals do not equal 123');
  const inputSeals = new Map();

  const canonicalBytes = readRequired(CANONICAL_PATH);
  assert(sha256(canonicalBytes) === CANONICAL_SHA256, 'canonical stream SHA-256 mismatch');
  inputSeals.set(CANONICAL_PATH, CANONICAL_SHA256);
  const canonical = parseStrictJsonl(CANONICAL_PATH, canonicalBytes);
  assert(canonical.rows.length === TOTAL_CANONICAL_ROWS, 'canonical stream must contain 188 rows');
  const canonicalMap = new Map();
  canonical.rows.forEach(function (row, index) {
    nonemptyString(row.candidate_id, CANONICAL_PATH + ':' + (index + 1) + '.candidate_id');
    nonemptyString(row.title, CANONICAL_PATH + ':' + (index + 1) + '.title');
    nonemptyString(row.instance_key, CANONICAL_PATH + ':' + (index + 1) + '.instance_key');
    assert(!canonicalMap.has(row.candidate_id), 'duplicate canonical candidate ID: ' + row.candidate_id);
    canonicalMap.set(row.candidate_id, {
      row,
      line: index + 1,
      rawSha256: sha256(canonical.rawLineBytes[index]),
    });
  });

  const validationBytes = readRequired(CENTRAL_VALIDATION_PATH);
  const validationSha = sha256(validationBytes);
  inputSeals.set(CENTRAL_VALIDATION_PATH, validationSha);
  const validation = parseStrictJsonl(CENTRAL_VALIDATION_PATH, validationBytes);
  assert(validation.rows.length === TOTAL_CANONICAL_ROWS, 'central validation must contain 188 rows');
  const validationEntries = validation.rows.map(function (row, index) {
    const line = index + 1;
    const label = CENTRAL_VALIDATION_PATH + ':' + line;
    validateCentralValidationRow(row, label);
    assert(row.canonical_line === line, label + '.canonical_line mismatch');
    const canonicalEntry = canonicalMap.get(row.candidate_id);
    assert(canonicalEntry && canonicalEntry.line === line, label + '.candidate_id does not match canonical order');
    assert(row.canonical_row_sha256 === canonicalEntry.rawSha256, label + '.canonical_row_sha256 mismatch');
    return {
      row,
      line,
      rawSha256: sha256(validation.rawLineBytes[index]),
      canonical: canonicalEntry.row,
    };
  });
  assert(new Set(validationEntries.map(function (entry) { return entry.row.candidate_id; })).size
    === TOTAL_CANONICAL_ROWS, 'central validation candidate IDs are not unique');
  const dispositionCounts = countsBy(validationEntries.map(function (entry) { return entry.row.disposition; }));
  assert(dispositionCounts.reportable === EXPECTED_REPORTABLE_ROWS
    && dispositionCounts.needs_review === EXPECTED_NEEDS_REVIEW_ROWS
    && dispositionCounts.not_reportable === EXPECTED_NOT_REPORTABLE_ROWS,
  'central validation counts do not match frozen 101/22/65 result');

  const centralManifestBytes = readRequired(CENTRAL_MANIFEST_PATH);
  const centralManifestSha = sha256(centralManifestBytes);
  inputSeals.set(CENTRAL_MANIFEST_PATH, centralManifestSha);
  const centralManifest = parsePrettyJson(CENTRAL_MANIFEST_PATH, centralManifestBytes);
  validateCentralManifest(centralManifest, validationBytes);

  const threatModelBytes = readRequired(THREAT_MODEL_PATH);
  assert(threatModelBytes.at(-1) === 0x0a && !threatModelBytes.includes(0x0d),
    THREAT_MODEL_PATH + ' must be LF-terminated with no CR bytes');
  const threatModelText = threatModelBytes.toString('utf8');
  assert(Buffer.from(threatModelText).equals(threatModelBytes), THREAT_MODEL_PATH + ' is not valid UTF-8');
  const threatModelLines = threatModelText.trimEnd().split('\n');
  assert(threatModelLines.at(-2) === 'Repository: ' + TARGET_REPOSITORY_ID,
    THREAT_MODEL_PATH + ' repository target ID mismatch');
  assert(threatModelLines.at(-1) === 'Version: ' + TARGET_COMMIT,
    THREAT_MODEL_PATH + ' immutable version mismatch');
  const threatModelSha = sha256(threatModelBytes);
  inputSeals.set(THREAT_MODEL_PATH, threatModelSha);

  const ledgerInfo = inspectCentralLedgers(validationEntries, centralManifest, inputSeals);
  const eligibleEntries = validationEntries.filter(function (entry) {
    return ELIGIBLE_DISPOSITIONS.includes(entry.row.disposition);
  });
  assert(eligibleEntries.length === EXPECTED_ELIGIBLE_ROWS, 'eligible validation rows must equal 123');

  const approvedBuilderBytes = readRequired(APPROVED_ATTACK_BUILDER_PATH);
  assert(sha256(approvedBuilderBytes) === APPROVED_ATTACK_BUILDER_SHA256,
    'approved attack-path builder bytes changed; re-audit schema and policy before materialization');
  inputSeals.set(APPROVED_ATTACK_BUILDER_PATH, APPROVED_ATTACK_BUILDER_SHA256);

  const outputMap = new Map();
  const batchPlans = [];
  const allCandidateIds = [];
  let copiedReportFiles = 0;
  for (const batch of BATCHES) {
    assert(/^[a-f0-9]{64}$/.test(batch.draftSha256), batch.id + ' draft hash pin is not frozen');
    assert(/^[a-f0-9]{64}$/.test(batch.reportsBundleSha256), batch.id + ' report-bundle hash pin is not frozen');
    const expected = eligibleEntries.filter(function (entry) {
      return entry.line >= batch.start && entry.line <= batch.end;
    });
    assert(expected.length === batch.expectedEligibleRows,
      batch.id + ' eligible count mismatch: expected ' + batch.expectedEligibleRows + '; got ' + expected.length);
    validateBatchOutputDirectory(batch);

    const draftBytes = readRequired(batch.draftPath);
    const draftSha = sha256(draftBytes);
    assert(draftSha === batch.draftSha256, batch.draftPath + ' differs from its frozen cross-audit hash');
    inputSeals.set(batch.draftPath, draftSha);
    const draft = parseStrictJsonl(batch.draftPath, draftBytes);
    assert(draft.rows.length === expected.length,
      batch.draftPath + ' row count mismatch: expected ' + expected.length + '; got ' + draft.rows.length);

    const expectedReportNames = expected.map(function (entry) { return entry.row.candidate_id + '.md'; });
    reportInventory(batch.sourceReportsDir, expectedReportNames, false);
    if (batch.sourceReportsDir !== batch.outputReportsDir) {
      reportInventory(batch.outputReportsDir, expectedReportNames, true);
    }

    const reportEntries = [];
    const outputRows = [];
    draft.rows.forEach(function (semantic, index) {
      const source = expected[index];
      const label = batch.draftPath + ':' + (index + 1);
      validateSemanticDraft(semantic, label);
      assert(semantic.candidate_id === source.row.candidate_id, label + '.candidate_id/order mismatch');
      const sourceReportPath = batch.sourceReportsDir + '/' + semantic.candidate_id + '.md';
      const outputReportPath = batch.outputReportsDir + '/' + semantic.candidate_id + '.md';
      const reportBytes = readRequired(sourceReportPath);
      validateReportBytes(sourceReportPath, reportBytes);
      const reportSha = sha256(reportBytes);
      inputSeals.set(sourceReportPath, reportSha);
      reportEntries.push({
        candidate_id: semantic.candidate_id,
        path: sourceReportPath,
        bytes: reportBytes.length,
        sha256: reportSha,
      });
      if (sourceReportPath !== outputReportPath) {
        outputMap.set(outputReportPath, reportBytes);
        copiedReportFiles += 1;
      }
      const ledger = ledgerInfo.get(semantic.candidate_id);
      assert(ledger && ledger.path === source.row.canonical_ledger_path,
        label + ' has no matching adopted post-central ledger');
      const output = {
        schema_version: 'deep-security-attack-path-staging-v1',
        event: 'centralized_attack_path_receipt',
        candidate_id: semantic.candidate_id,
        validation_line: source.line,
        source_validation_path: CENTRAL_VALIDATION_PATH,
        source_validation_stream_sha256: validationSha,
        source_validation_row_sha256: source.rawSha256,
        central_validation_manifest_path: CENTRAL_MANIFEST_PATH,
        central_validation_manifest_sha256: centralManifestSha,
        threat_model_path: THREAT_MODEL_PATH,
        threat_model_sha256: threatModelSha,
        target: TARGET_COMMIT,
        validation_disposition: source.row.disposition,
        title: source.canonical.title,
        instance_key: source.canonical.instance_key,
        ledger_row_id: CENTRAL_VALIDATION_RUN_ID + ':' + semantic.candidate_id,
        affected_locations: source.row.affected_locations,
        attack_path_steps: semantic.attack_path_steps,
        attack_path_facts: semantic.attack_path_facts,
        counterevidence: semantic.counterevidence,
        impact_level: semantic.impact_level,
        likelihood_level: semantic.likelihood_level,
        critical_criteria_satisfied: semantic.critical_criteria_satisfied,
        hard_suppression: semantic.hard_suppression,
        hard_suppression_rationale: semantic.hard_suppression_rationale,
        calibrated_severity: semantic.calibrated_severity,
        final_policy_decision: semantic.final_policy_decision,
        final_priority: semantic.final_priority,
        confidence: semantic.confidence,
        proof_gaps: semantic.proof_gaps,
        rationale: semantic.rationale,
        report_path: outputReportPath,
        report_sha256: reportSha,
        canonical_ledger_path: ledger.path,
        canonical_ledger_sha256: ledger.sha256,
      };
      exactKeys(output, OUTPUT_KEYS, label + '.materialized');
      assertNoExcludedEvidenceReferences(output, label + '.materialized');
      assert(deepEqual(semanticProjection(output), semantic),
        label + ' semantics changed during materialization');
      assert(deepEqual(output.affected_locations, source.row.affected_locations),
        label + ' affected_locations changed during materialization');
      outputRows.push(output);
      allCandidateIds.push(output.candidate_id);
    });
    const reportsBundle = reportBundleBytes(reportEntries);
    const reportsBundleSha = sha256(reportsBundle);
    assert(reportsBundleSha === batch.reportsBundleSha256,
      batch.sourceReportsDir + ' report bundle differs from its frozen cross-audit hash');
    const outputBytes = Buffer.from(outputRows.map(function (row) { return JSON.stringify(row); }).join('\n') + '\n');
    outputMap.set(batch.outputPath, outputBytes);
    batchPlans.push({
      batch_id: batch.id,
      canonical_lines: { start: batch.start, end: batch.end },
      rows: outputRows.length,
      semantic_draft: { path: batch.draftPath, bytes: draftBytes.length, sha256: draftSha },
      source_reports: {
        directory: batch.sourceReportsDir,
        bundle_bytes: reportsBundle.length,
        bundle_sha256: reportsBundleSha,
        reports: reportEntries,
      },
      materialized_output: { path: batch.outputPath, bytes: outputBytes.length, sha256: sha256(outputBytes) },
    });
  }
  assert(allCandidateIds.length === EXPECTED_ELIGIBLE_ROWS
    && new Set(allCandidateIds).size === EXPECTED_ELIGIBLE_ROWS,
  'materialized candidate coverage must contain 123 unique IDs');
  assert(copiedReportFiles === 0, 'all 123 final reports must already be at their builder-facing paths');
  eligibleEntries.forEach(function (entry, index) {
    assert(allCandidateIds[index] === entry.row.candidate_id,
      'global materialized candidate order mismatch at eligible row ' + (index + 1));
  });

  const builderBytes = fs.readFileSync(SCRIPT_PATH);
  const builderRelativePath = path.relative(SCAN_ROOT, SCRIPT_PATH).split(path.sep).join('/');
  assertSafeRegularFileAbsolute(SCRIPT_PATH, builderRelativePath);
  const builderSha = sha256(builderBytes);
  inputSeals.set(builderRelativePath, builderSha);

  const repositoryForManifest = { ...repositoryState };
  delete repositoryForManifest.rawPorcelain;
  const manifest = {
    schema_version: 'deep-security-attack-path-staging-materialization-manifest-v1',
    event: 'attack_path_staging_materialization_manifest',
    materialization_run_id: MATERIALIZATION_RUN_ID,
    target: { commit: TARGET_COMMIT, tree: TARGET_TREE, repository_id: TARGET_REPOSITORY_ID },
    safety_gate: {
      default_mode: 'check-only',
      write_requires_exact_manifest_sha256: true,
      clean_repository_required: true,
      exact_semantic_preservation_required: true,
      crossaudit_draft_and_report_hashes_required: true,
      central_adoption_required: true,
      postcentral_preattack_ledgers_required: true,
    },
    repository: repositoryForManifest,
    materializer: { path: builderRelativePath, bytes: builderBytes.length, sha256: builderSha },
    approved_attack_builder: {
      path: APPROVED_ATTACK_BUILDER_PATH,
      bytes: approvedBuilderBytes.length,
      sha256: APPROVED_ATTACK_BUILDER_SHA256,
      exact_staging_keys: OUTPUT_KEYS.length,
    },
    canonical: { path: CANONICAL_PATH, bytes: canonicalBytes.length, rows: canonical.rows.length, sha256: CANONICAL_SHA256 },
    central_validation: {
      path: CENTRAL_VALIDATION_PATH,
      bytes: validationBytes.length,
      rows: validationEntries.length,
      sha256: validationSha,
      manifest_path: CENTRAL_MANIFEST_PATH,
      manifest_sha256: centralManifestSha,
      disposition_counts: dispositionCounts,
      eligible_rows: eligibleEntries.length,
    },
    threat_model: { path: THREAT_MODEL_PATH, bytes: threatModelBytes.length, sha256: threatModelSha },
    postcentral_ledgers: validationEntries.map(function (entry) {
      const ledger = ledgerInfo.get(entry.row.candidate_id);
      return { candidate_id: entry.row.candidate_id, path: ledger.path, sha256: ledger.sha256 };
    }),
    sealed_inputs: [...inputSeals.entries()].sort(function (left, right) {
      return left[0] < right[0] ? -1 : left[0] > right[0] ? 1 : 0;
    }).map(function (entry) {
      return { path: entry[0], sha256: entry[1] };
    }),
    batches: batchPlans,
    outputs: [...outputMap.entries()].map(function (entry) {
      return { path: entry[0], bytes: entry[1].length, sha256: sha256(entry[1]) };
    }),
    invariants: {
      exact_output_schema_keys: OUTPUT_KEYS.length,
      exact_eligible_rows: EXPECTED_ELIGIBLE_ROWS,
      exact_materialized_jsonl_files: BATCHES.length,
      exact_copied_report_files: copiedReportFiles,
      exact_batch_rows: Object.fromEntries(BATCHES.map(function (batch) {
        return [batch.id, batch.expectedEligibleRows];
      })),
      semantic_projection_preserved_exactly: true,
      adoption_dependent_fields_derived_only: true,
      report_bytes_preserved_exactly: true,
      all_188_postcentral_ledgers_authenticated: true,
      attack_path_receipts_present_before_materialization: 0,
    },
  };
  const manifestBytes = jsonBytes(manifest);
  const manifestSha = sha256(manifestBytes);
  outputMap.set(MATERIALIZATION_MANIFEST_PATH, manifestBytes);

  assertNoOrphanTransactionFiles(outputMap);
  const existingManifest = readOutputState(MATERIALIZATION_MANIFEST_PATH);
  if (existingManifest === null) {
    for (const relativePath of outputMap.keys()) {
      if (relativePath === MATERIALIZATION_MANIFEST_PATH) continue;
      assert(readOutputState(relativePath) === null,
        'uncommitted materialization output requires manual review: ' + relativePath);
    }
  } else {
    assert(existingManifest.bytes.equals(manifestBytes),
      'existing materialization manifest differs from the current sealed plan');
    for (const [relativePath, plannedBytes] of outputMap) {
      const state = readOutputState(relativePath);
      assert(state !== null && state.bytes.equals(plannedBytes),
        'materialization commit marker exists but output differs: ' + relativePath);
    }
  }

  const expectedOutputHashes = new Map([...outputMap.entries()].map(function (entry) {
    return [entry[0], sha256(entry[1])];
  }));
  const wouldChangeFiles = [];
  for (const [relativePath, plannedBytes] of outputMap) {
    const state = readOutputState(relativePath);
    if (state === null || !state.bytes.equals(plannedBytes)) wouldChangeFiles.push(relativePath);
  }
  return {
    inputSeals,
    outputMap,
    expectedOutputHashes,
    manifestSha,
    wouldChangeFiles,
    stdout: {
      mode: null,
      target: TARGET_COMMIT,
      output_schema_keys: OUTPUT_KEYS.length,
      central_validation_sha256: validationSha,
      central_validation_manifest_sha256: centralManifestSha,
      threat_model_sha256: threatModelSha,
      eligible_rows: eligibleEntries.length,
      batch_rows: Object.fromEntries(batchPlans.map(function (batch) {
        return [batch.batch_id, batch.rows];
      })),
      semantic_draft_sha256: Object.fromEntries(batchPlans.map(function (batch) {
        return [batch.batch_id, batch.semantic_draft.sha256];
      })),
      report_bundle_sha256: Object.fromEntries(batchPlans.map(function (batch) {
        return [batch.batch_id, batch.source_reports.bundle_sha256];
      })),
      materialized_jsonl_sha256: Object.fromEntries(batchPlans.map(function (batch) {
        return [batch.batch_id, batch.materialized_output.sha256];
      })),
      planned_manifest_sha256: manifestSha,
      planned_output_files: outputMap.size,
      would_change_files: wouldChangeFiles,
      errors: [],
    },
  };
}

function verifyInputsUnchanged(plan) {
  for (const [relativePath, expectedSha] of plan.inputSeals) {
    assert(sha256(readRequired(relativePath)) === expectedSha, 'sealed input changed: ' + relativePath);
  }
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const repositoryState = captureRepositoryState();
  const plan = buildPlan(repositoryState);
  plan.stdout.mode = args.mode;
  verifyInputsUnchanged(plan);
  verifyRepositoryUnchanged(repositoryState);

  if (args.mode === 'write') {
    assert(args.manifestSha256 === plan.manifestSha,
      'write seal mismatch: supplied ' + args.manifestSha256 + '; current plan is ' + plan.manifestSha);
    verifyInputsUnchanged(plan);
    verifyRepositoryUnchanged(repositoryState);
    writeTransaction(plan.outputMap, plan.expectedOutputHashes, function () {
      verifyInputsUnchanged(plan);
      verifyRepositoryUnchanged(repositoryState);
      const postPlan = buildPlan(repositoryState);
      assert(postPlan.manifestSha === plan.manifestSha, 'post-write materialization plan is not idempotent');
      assert(postPlan.wouldChangeFiles.length === 0,
        'post-write plan still has changes: ' + postPlan.wouldChangeFiles.join(','));
    });
    plan.stdout.would_change_files = [];
  }
  console.log(JSON.stringify(plan.stdout, null, 2));
}

try {
  main();
} catch (error) {
  console.error(JSON.stringify({
    mode: process.argv.includes('--write') ? 'write' : 'check-only',
    errors: [error.message],
  }, null, 2));
  process.exitCode = 1;
}
