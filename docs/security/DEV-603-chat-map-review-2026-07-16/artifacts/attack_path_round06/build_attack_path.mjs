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
const CENTRAL_VALIDATION_RUN_ID = 'central-validation-round-06';
const ATTACK_PATH_RUN_ID = 'attack-path-round-06';
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
const HARD_SUPPRESSIONS = [
  'none',
  'unachievable_preconditions',
  'out_of_scope',
];

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

const ATTACK_KEYS = [
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

const BATCHES = [
  { id: 'batch-01', start: 1, end: 38, expectedEligibleRows: 33 },
  { id: 'batch-02', start: 39, end: 76, expectedEligibleRows: 10 },
  { id: 'batch-03', start: 77, end: 113, expectedEligibleRows: 24 },
  { id: 'batch-04', start: 114, end: 151, expectedEligibleRows: 23 },
  { id: 'batch-05', start: 152, end: 188, expectedEligibleRows: 33 },
].map(function (batch) {
  return {
    ...batch,
    attackPath: 'artifacts/attack_path_round06/' + batch.id + '/attack_path.jsonl',
    reportsDir: 'artifacts/attack_path_round06/' + batch.id + '/reports',
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

function assertSafeOutputParent(relativePath) {
  const absolutePath = asAbsolute(relativePath);
  assertSafeDirectoryAbsolute(path.dirname(absolutePath), 'output parent for ' + relativePath);
  if (fs.existsSync(absolutePath)) {
    assertSafeRegularFileAbsolute(absolutePath, relativePath);
  }
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

function confidenceNumber(value, label) {
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
    for (const [key, item] of Object.entries(value)) {
      assertNoExcludedEvidenceReferences(item, label + '.' + key);
    }
  }
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
  confidenceNumber(value.confidence, label + '.confidence');
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

function validatePolicy(row, label) {
  assert(IMPACT_LEVELS.includes(row.impact_level), label + '.impact_level is outside the enum');
  assert(LIKELIHOOD_LEVELS.includes(row.likelihood_level), label + '.likelihood_level is outside the enum');
  assert(typeof row.critical_criteria_satisfied === 'boolean', label + '.critical_criteria_satisfied must be boolean');
  if (row.critical_criteria_satisfied) {
    assert(row.impact_level === 'high' && row.likelihood_level === 'high',
      label + '.critical_criteria_satisfied may be true only for high/high');
  }
  assert(HARD_SUPPRESSIONS.includes(row.hard_suppression), label + '.hard_suppression is outside the enum');
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
    label + '.hard_suppression must equal the structured-fact result ' + expectedSuppression);

  if (row.hard_suppression !== 'none') {
    assert(row.calibrated_severity === 'ignore', label + ' hard suppression requires calibrated_severity=ignore');
    assert(row.final_policy_decision === 'ignore', label + ' hard suppression requires final_policy_decision=ignore');
    assert(row.final_priority === 'none', label + ' hard suppression requires final_priority=none');
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

function visibleMarkdownLines(text) {
  const withoutComments = text.replace(/<!--[\s\S]*?(?:-->|$)/g, function (comment) {
    return comment.replace(/[^\n]/g, ' ');
  });
  const lines = withoutComments.split('\n');
  const visible = [];
  let fence = null;
  lines.forEach(function (line) {
    if (fence !== null) {
      const closing = line.match(/^ {0,3}(`{3,}|~{3,})[ \t]*$/);
      if (closing !== null && closing[1][0] === fence.character && closing[1].length >= fence.length) {
        fence = null;
      }
      visible.push('');
      return;
    }
    const opening = line.match(/^ {0,3}(`{3,}|~{3,})(.*)$/);
    if (opening !== null) {
      fence = { character: opening[1][0], length: opening[1].length };
      visible.push('');
      return;
    }
    visible.push(line);
  });
  return visible;
}

function reportSection(lines, heading, relativePath) {
  const pattern = new RegExp('^(#{1,6})[ \\t]+' + heading + '[ \\t]*$');
  const matches = [];
  lines.forEach(function (line, index) {
    const match = line.match(pattern);
    if (match !== null) matches.push({ index, level: match[1].length });
  });
  assert(matches.length === 1,
    relativePath + ' must contain exactly one visible Markdown heading: ' + heading);
  const start = matches[0];
  let end = lines.length;
  for (let index = start.index + 1; index < lines.length; index += 1) {
    const match = lines[index].match(/^(#{1,6})[ \\t]+/);
    if (match !== null && match[1].length <= start.level) {
      end = index;
      break;
    }
  }
  const section = lines.slice(start.index + 1, end);
  assert(section.some(function (line) { return line.trim().length > 0; }),
    relativePath + ' has an empty visible Markdown section: ' + heading);
  return section;
}

function requireExactReportLine(lines, label, value, relativePath) {
  const expected = '- ' + label + ': ' + String(value);
  assert(lines.includes(expected), relativePath + ' is missing exact visible line: ' + expected);
}

function requireFactsContent(lines, facts, relativePath) {
  requireExactReportLine(lines, 'Context', facts.context, relativePath);
  requireExactReportLine(lines, 'In-scope status', facts.in_scope_status, relativePath);
  requireExactReportLine(lines, 'In-scope reasoning', facts.in_scope_reasoning, relativePath);
  requireExactReportLine(lines, 'Exposure', facts.exposure, relativePath);
  requireExactReportLine(lines, 'Identity', facts.identity, relativePath);
  requireExactReportLine(lines, 'Cross-boundary behavior', facts.cross_boundary_behavior, relativePath);
  requireExactReportLine(lines, 'Vector', facts.vector, relativePath);
  requireExactReportLine(lines, 'Precondition feasibility', facts.precondition_feasibility, relativePath);
  requireExactReportLine(lines, 'Attacker input control', facts.attacker_input_control, relativePath);
  requireExactReportLine(lines, 'Category', facts.category, relativePath);
  requireExactReportLine(lines, 'Auth scope', facts.auth_scope, relativePath);
  requireExactReportLine(lines, 'Impact surface', facts.impact_surface, relativePath);
  requireExactReportLine(lines, 'Target reach', facts.target_reach, relativePath);
  requireExactReportLine(lines, 'Confidence', facts.confidence, relativePath);

  const sectionText = lines.join('\n');
  const listFields = [
    'assumptions',
    'preconditions',
    'mitigations_already_present',
    'secrets_references',
    'blindspots',
    'controls',
  ];
  listFields.forEach(function (field) {
    facts[field].forEach(function (value, index) {
      assert(sectionText.includes(value),
        relativePath + ' Attack Path Facts omits ' + field + '[' + index + ']');
    });
  });
  const evidenceFields = ATTACK_FACT_KEYS.filter(function (key) { return key.endsWith('_evidence'); });
  evidenceFields.forEach(function (field) {
    facts[field].forEach(function (evidence, index) {
      EVIDENCE_KEYS.forEach(function (key) {
        assert(sectionText.includes(evidence[key]),
          relativePath + ' Attack Path Facts omits ' + field + '[' + index + '].' + key);
      });
    });
  });
}

function validateVisibleReport(relativePath, bytes, row) {
  assert(bytes.at(-1) === 0x0a, relativePath + ' must end with LF');
  assert(!bytes.includes(0x0d), relativePath + ' must not contain CR bytes');
  const text = bytes.toString('utf8');
  assert(Buffer.from(text).equals(bytes), relativePath + ' is not valid UTF-8');
  assert(!containsExcludedEvidencePath(text),
    relativePath + ' contains an excluded quarantine or preterminal-validation-drafts path reference');
  const lines = visibleMarkdownLines(text);
  const visibleText = lines.join('\n');
  requireExactReportLine(lines, 'Candidate ID', row.candidate_id, relativePath);
  requireExactReportLine(lines, 'Title', row.title, relativePath);
  requireExactReportLine(lines, 'Instance key', row.instance_key, relativePath);
  requireExactReportLine(lines, 'Ledger row ID', row.ledger_row_id, relativePath);

  const stepsSection = reportSection(lines, 'Attack Path Steps', relativePath);
  row.attack_path_steps.forEach(function (step, index) {
    const expected = (index + 1) + '. ' + step;
    assert(stepsSection.includes(expected),
      relativePath + ' is missing exact numbered attack_path_steps[' + index + ']: ' + expected);
  });

  const factsSection = reportSection(lines, 'Attack Path Facts', relativePath);
  requireFactsContent(factsSection, row.attack_path_facts, relativePath);

  const counterevidenceSection = reportSection(lines, 'Counterevidence', relativePath);
  row.counterevidence.forEach(function (counterevidence, index) {
    const expected = '- ' + counterevidence;
    assert(counterevidenceSection.includes(expected),
      relativePath + ' is missing exact counterevidence[' + index + '] bullet: ' + expected);
  });

  const severitySection = reportSection(lines, 'Severity Calibration', relativePath);
  requireExactReportLine(severitySection, 'Impact level', row.impact_level, relativePath);
  requireExactReportLine(severitySection, 'Likelihood level', row.likelihood_level, relativePath);
  requireExactReportLine(severitySection, 'Critical criteria satisfied', row.critical_criteria_satisfied, relativePath);
  requireExactReportLine(severitySection, 'Hard suppression', row.hard_suppression, relativePath);
  requireExactReportLine(severitySection, 'Hard suppression rationale', row.hard_suppression_rationale, relativePath);
  requireExactReportLine(severitySection, 'Calibrated severity', row.calibrated_severity, relativePath);

  const policySection = reportSection(lines, 'Final Policy Decision', relativePath);
  requireExactReportLine(policySection, 'Final policy decision', row.final_policy_decision, relativePath);
  requireExactReportLine(policySection, 'Final priority', row.final_priority, relativePath);
  requireExactReportLine(policySection, 'Rationale', row.rationale, relativePath);

  row.affected_locations.forEach(function (location, index) {
    assert(visibleText.includes(location.path), relativePath + ' omits visible affected_locations[' + index + '].path');
    assert(visibleText.includes(location.lines), relativePath + ' omits visible affected_locations[' + index + '].lines');
    if (typeof location.label === 'string' && location.label.trim().length > 0) {
      assert(visibleText.includes(location.label), relativePath + ' omits visible affected_locations[' + index + '].label');
    }
    if (typeof location.detail === 'string' && location.detail.trim().length > 0) {
      assert(visibleText.includes(location.detail), relativePath + ' omits visible affected_locations[' + index + '].detail');
    }
  });
  return text;
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
  assert(after.rawPorcelain === before.rawPorcelain, 'repository porcelain status changed during assembly');
  assert(after.observed_head === before.observed_head, 'repository HEAD changed during assembly');
  assert(after.observed_head_tree === before.observed_head_tree, 'repository HEAD tree changed during assembly');
}

function parseArgs(argv) {
  let mode = 'check-only';
  let explicitMode = false;
  let manifestSha256 = null;
  for (const arg of argv) {
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
  }
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

function jsonlRowBytes(value) {
  return Buffer.from(JSON.stringify(value) + '\n');
}

function countsBy(values, preferredOrder) {
  const order = preferredOrder || [];
  const counts = new Map();
  values.forEach(function (value) {
    counts.set(value, (counts.get(value) || 0) + 1);
  });
  const keys = [
    ...order.filter(function (key) { return counts.has(key); }),
    ...[...counts.keys()].filter(function (key) { return !order.includes(key); }).sort(),
  ];
  return Object.fromEntries(keys.map(function (key) { return [key, counts.get(key)]; }));
}

function markdownCell(value) {
  return String(value).replaceAll('\\', '\\\\').replaceAll('|', '\\|').replaceAll('\r', ' ').replaceAll('\n', '<br>');
}

function bytesBeforeFinalRow(parsed) {
  assert(parsed.rawLines.length > 1, 'ledger has no rows before marked suffix');
  return Buffer.from(parsed.rawLines.slice(0, -1).join('\n') + '\n');
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
  confidenceNumber(row.confidence, label + '.confidence');
  ['source', 'closest_control', 'sink', 'impact', 'fix_direction', 'review_tier', 'rationale'].forEach(function (key) {
    nonemptyString(row[key], label + '.' + key);
  });
  ['preconditions', 'counterevidence', 'proof_gaps'].forEach(function (key) {
    stringArray(row[key], label + '.' + key);
  });
  validateEvidence(row.evidence, label + '.evidence');
  validateAffectedLocations(row.affected_locations, label + '.affected_locations');
  const ledgerPath = 'artifacts/05_findings/' + row.candidate_id + '/candidate_ledger.jsonl';
  assert(row.canonical_ledger_path === ledgerPath, label + '.canonical_ledger_path mismatch');
  assert(/^[a-f0-9]{64}$/.test(row.canonical_ledger_sha256), label + '.canonical_ledger_sha256 must be SHA-256');
}

function validateCentralManifest(manifest, manifestSha, validationBytes, validationRows) {
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
    && manifest.consolidated_outputs.validation.rows === validationRows.length,
  CENTRAL_MANIFEST_PATH + '.validation output seal mismatch');
  assert(manifest.invariants && manifest.invariants.exact_rows === TOTAL_CANONICAL_ROWS
    && manifest.invariants.canonical_validation_receipts_planned === TOTAL_CANONICAL_ROWS
    && manifest.invariants.canonical_attack_path_receipts_planned === 0,
  CENTRAL_MANIFEST_PATH + '.invariants mismatch');
  assert(Array.isArray(manifest.ledger_plan) && manifest.ledger_plan.length === TOTAL_CANONICAL_ROWS,
    CENTRAL_MANIFEST_PATH + '.ledger_plan must have 188 rows');
  return {
    path: CENTRAL_MANIFEST_PATH,
    bytes: jsonBytes(manifest).length,
    sha256: manifestSha,
  };
}

function inspectCentralAdoption(validationEntries, centralManifest) {
  const manifestLedgerPlans = new Map();
  centralManifest.ledger_plan.forEach(function (plan, index) {
    nonemptyString(plan.candidate_id, CENTRAL_MANIFEST_PATH + '.ledger_plan[' + index + '].candidate_id');
    assert(!manifestLedgerPlans.has(plan.candidate_id), CENTRAL_MANIFEST_PATH + ' has duplicate ledger plan IDs');
    manifestLedgerPlans.set(plan.candidate_id, plan);
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
    assert(centralIndexes.length === 1, ledgerPath + ' must contain exactly one marked central validation receipt');
    assert(attackIndexes.length <= 1, ledgerPath + ' contains duplicate marked attack-path receipts');
    const eligible = ELIGIBLE_DISPOSITIONS.includes(row.disposition);
    assert(eligible || attackIndexes.length === 0,
      ledgerPath + ' has an attack-path receipt for an ineligible validation row');
    if (attackIndexes.length === 1) {
      assert(attackIndexes[0] === parsed.rows.length - 1, ledgerPath + ' marked attack-path receipt is not final');
    }
    const expectedCentralIndex = parsed.rows.length - 1 - attackIndexes.length;
    assert(centralIndexes[0] === expectedCentralIndex,
      ledgerPath + ' marked central validation receipt is not immediately before attack-path state');
    const centralIndex = centralIndexes[0];
    const centralReceipt = parsed.rows[centralIndex];
    const preCentralRawLines = parsed.rawLines.slice(0, centralIndex);
    assert(preCentralRawLines.length > 0, ledgerPath + ' has no historical rows before central validation');
    const preCentralBytes = Buffer.from(preCentralRawLines.join('\n') + '\n');
    const preCentralSha = sha256(preCentralBytes);
    const manifestPlan = manifestLedgerPlans.get(row.candidate_id);
    assert(manifestPlan && manifestPlan.path === ledgerPath,
      CENTRAL_MANIFEST_PATH + ' has no matching ledger plan for ' + row.candidate_id);
    assert(preCentralSha === row.canonical_ledger_sha256,
      ledgerPath + ' historical prefix does not match source validation canonical_ledger_sha256');
    assert(centralReceipt.canonical_ledger_preappend_sha256 === preCentralSha,
      ledgerPath + ' central receipt preappend hash does not authenticate the historical prefix');
    assert(manifestPlan.preappend_sha256 === preCentralSha,
      ledgerPath + ' central manifest preappend hash does not authenticate the historical prefix');
    assert(centralReceipt.schema_version === 'deep-security-scan-candidate-ledger-v1'
      && centralReceipt.event === 'canonical_validation_receipt' && centralReceipt.phase === 'validation'
      && centralReceipt.validation_run_id === CENTRAL_VALIDATION_RUN_ID
      && centralReceipt.canonical_candidate_id === row.candidate_id,
      ledgerPath + ' central marker is not a canonical validation receipt');
    assert(centralReceipt.target === TARGET_COMMIT && centralReceipt.target_tree === TARGET_TREE,
      ledgerPath + ' central receipt target mismatch');
    assert(centralReceipt.centralized_validation_performed === true
      && centralReceipt.centralized_attack_path_analysis_performed === false,
    ledgerPath + ' central receipt phase flags mismatch');
    assert(centralReceipt.canonical_row_sha256 === row.canonical_row_sha256,
      ledgerPath + ' central receipt canonical row hash mismatch');
    assert(centralReceipt.canonical_line === row.canonical_line,
      ledgerPath + ' central receipt canonical_line mismatch');
    assert(centralReceipt.canonical_ledger_path === row.canonical_ledger_path,
      ledgerPath + ' central receipt canonical_ledger_path mismatch');
    assert(centralReceipt.validation_disposition === row.disposition,
      ledgerPath + ' central receipt validation_disposition mismatch');
    assert(centralReceipt.severity === row.severity,
      ledgerPath + ' central receipt severity mismatch');
    assert(centralReceipt.confidence === row.confidence,
      ledgerPath + ' central receipt confidence mismatch');
    assert(centralReceipt.source_validation_artifact
      && centralReceipt.source_validation_artifact.canonical_line === entry.line
      && centralReceipt.source_validation_artifact.row_sha256 === entry.rawSha256,
    ledgerPath + ' central receipt source validation row backlink mismatch');
    assert(deepEqual(centralReceipt.source_validation, row),
      ledgerPath + ' central receipt does not embed the exact current validation row');

    const centralBaseBytes = attackIndexes.length === 1 ? bytesBeforeFinalRow(parsed) : currentBytes;
    const centralBaseSha = sha256(centralBaseBytes);
    assert(manifestPlan.planned_sha256 === centralBaseSha,
      ledgerPath + ' does not match the adopted central manifest ledger hash');
    assert(manifestPlan.receipt_row_sha256 === sha256(parsed.rawLineBytes[centralIndex]),
      ledgerPath + ' central receipt row does not match the central manifest receipt hash');
    ledgerInfo.set(row.candidate_id, {
      path: ledgerPath,
      currentBytes,
      parsed,
      preCentralSha,
      centralBaseBytes,
      centralBaseSha,
      attackReceiptAlreadyPresent: attackIndexes.length === 1,
    });
  });

  assert(ledgerInfo.size === TOTAL_CANONICAL_ROWS, 'central adoption did not cover all 188 ledgers');
  return ledgerInfo;
}

function readOutputState(relativePath) {
  const absolutePath = assertSafeOutputParent(relativePath);
  if (!fs.existsSync(absolutePath)) return null;
  return {
    bytes: fs.readFileSync(absolutePath),
    mode: fs.statSync(absolutePath).mode & 0o777,
  };
}

function assertNoOrphanTransactionFiles(outputMap) {
  const parents = new Set();
  for (const relativePath of outputMap.keys()) {
    const absolutePath = assertSafeOutputParent(relativePath);
    parents.add(path.dirname(absolutePath));
  }
  const transactionName = /^\..+\.\d+\.[0-9a-f-]+\.(tmp|restore)$/i;
  for (const parent of parents) {
    assertSafeDirectoryAbsolute(parent, 'transaction output parent');
    const orphans = fs.readdirSync(parent).filter(function (name) {
      return transactionName.test(name);
    });
    assert(orphans.length === 0,
      'orphan transaction files require manual review before assembly: '
      + orphans.map(function (name) { return path.join(parent, name); }).join(', '));
  }
}

function writeTransaction(outputMap, expectedHashes, permittedStates, postWriteCheck) {
  const changedEntries = [];
  for (const [relativePath, plannedBytes] of outputMap) {
    const state = readOutputState(relativePath);
    if (state === null || !state.bytes.equals(plannedBytes)) {
      changedEntries.push([relativePath, plannedBytes]);
    }
  }
  if (changedEntries.length === 0) {
    postWriteCheck();
    return;
  }

  const snapshots = new Map();
  const stagedTemps = new Map();
  const rollbackTemps = new Set();
  const committedPaths = new Set();

  for (const [relativePath] of changedEntries) {
    const state = readOutputState(relativePath);
    snapshots.set(relativePath, state);
    const stateSha = state === null ? null : sha256(state.bytes);
    const permitted = permittedStates.get(relativePath);
    assert(permitted && permitted.includes(stateSha),
      'refusing to overwrite unexpected state at ' + relativePath + ': ' + (stateSha || 'missing'));
  }

  try {
    for (const [relativePath, plannedBytes] of changedEntries) {
      const absolutePath = assertSafeOutputParent(relativePath);
      const tempPath = path.join(path.dirname(absolutePath),
        '.' + path.basename(absolutePath) + '.' + process.pid + '.' + randomUUID() + '.tmp');
      stagedTemps.set(relativePath, tempPath);
      const snapshot = snapshots.get(relativePath);
      fs.writeFileSync(tempPath, plannedBytes, { flag: 'wx', mode: snapshot ? snapshot.mode : 0o644 });
      assertSafeRegularFileAbsolute(tempPath, 'staged temporary output');
      const descriptor = fs.openSync(tempPath, 'r');
      try {
        fs.fsyncSync(descriptor);
      } finally {
        fs.closeSync(descriptor);
      }
    }

    for (const [relativePath] of changedEntries) {
      const absolutePath = assertSafeOutputParent(relativePath);
      const snapshot = snapshots.get(relativePath);
      if (snapshot === null) {
        assert(!fs.existsSync(absolutePath), 'output appeared concurrently before rename: ' + relativePath);
      } else {
        assert(fs.readFileSync(absolutePath).equals(snapshot.bytes),
          'output changed concurrently before rename: ' + relativePath);
        assert((fs.statSync(absolutePath).mode & 0o777) === snapshot.mode,
          'output mode changed concurrently before rename: ' + relativePath);
      }
      assertSafeOutputParent(relativePath);
      fs.renameSync(stagedTemps.get(relativePath), absolutePath);
      stagedTemps.delete(relativePath);
      committedPaths.add(relativePath);
    }

    for (const [relativePath, plannedSha] of expectedHashes) {
      assert(sha256(readRequired(relativePath)) === plannedSha,
        'post-write hash mismatch for ' + relativePath);
    }
    postWriteCheck();
  } catch (error) {
    const rollbackFailures = [];
    for (const tempPath of stagedTemps.values()) {
      try {
        if (fs.existsSync(tempPath)) fs.unlinkSync(tempPath);
      } catch (cleanupError) {
        rollbackFailures.push('remove ' + tempPath + ': ' + cleanupError.message);
      }
    }
    const rollbackOrder = [...committedPaths].reverse();
    for (const relativePath of rollbackOrder) {
      const original = snapshots.get(relativePath);
      try {
        const absolutePath = assertSafeOutputParent(relativePath);
        if (original === null) {
          if (fs.existsSync(absolutePath)) fs.unlinkSync(absolutePath);
        } else {
          const restorePath = path.join(path.dirname(absolutePath),
            '.' + path.basename(absolutePath) + '.' + process.pid + '.' + randomUUID() + '.restore');
          rollbackTemps.add(restorePath);
          fs.writeFileSync(restorePath, original.bytes, { flag: 'wx', mode: original.mode });
          assertSafeRegularFileAbsolute(restorePath, 'rollback temporary output');
          fs.renameSync(restorePath, absolutePath);
          rollbackTemps.delete(restorePath);
        }
      } catch (restoreError) {
        rollbackFailures.push('restore ' + relativePath + ': ' + restoreError.message);
      }
    }
    for (const restorePath of rollbackTemps) {
      try {
        if (fs.existsSync(restorePath)) fs.unlinkSync(restorePath);
      } catch (cleanupError) {
        rollbackFailures.push('remove ' + restorePath + ': ' + cleanupError.message);
      }
    }
    for (const relativePath of rollbackOrder) {
      const original = snapshots.get(relativePath);
      try {
        const absolutePath = asAbsolute(relativePath);
        if (original === null) {
          assert(!fs.existsSync(absolutePath), 'rollback left new output: ' + relativePath);
        } else {
          assertSafeRegularFileAbsolute(absolutePath, relativePath);
          assert(fs.readFileSync(absolutePath).equals(original.bytes), 'rollback byte mismatch: ' + relativePath);
          assert((fs.statSync(absolutePath).mode & 0o777) === original.mode, 'rollback mode mismatch: ' + relativePath);
        }
      } catch (verifyError) {
        rollbackFailures.push('verify ' + relativePath + ': ' + verifyError.message);
      }
    }
    if (rollbackFailures.length > 0) {
      fail(error.message + '; rollback failures: ' + rollbackFailures.join('; '));
    }
    throw error;
  }
}

function buildPlan(repositoryState) {
  const inputSeals = new Map();

  const canonicalBytes = readRequired(CANONICAL_PATH);
  assert(sha256(canonicalBytes) === CANONICAL_SHA256, 'canonical stream SHA-256 mismatch');
  inputSeals.set(CANONICAL_PATH, CANONICAL_SHA256);
  const canonical = parseStrictJsonl(CANONICAL_PATH, canonicalBytes);
  assert(canonical.rows.length === TOTAL_CANONICAL_ROWS, 'canonical stream must have 188 rows');
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
  assert(validation.rows.length === TOTAL_CANONICAL_ROWS, 'central validation stream must have 188 rows');
  const validationEntries = validation.rows.map(function (row, index) {
    const line = index + 1;
    const label = CENTRAL_VALIDATION_PATH + ':' + line;
    validateCentralValidationRow(row, label);
    assert(row.canonical_line === line, label + '.canonical_line must equal ' + line);
    const canonicalEntry = canonicalMap.get(row.candidate_id);
    assert(canonicalEntry && canonicalEntry.line === line, label + '.candidate_id does not match canonical line');
    assert(row.canonical_row_sha256 === canonicalEntry.rawSha256, label + '.canonical_row_sha256 mismatch');
    return {
      row,
      line,
      rawLineBytes: validation.rawLineBytes[index],
      rawSha256: sha256(validation.rawLineBytes[index]),
      canonical: canonicalEntry.row,
    };
  });
  assert(new Set(validationEntries.map(function (entry) { return entry.row.candidate_id; })).size === TOTAL_CANONICAL_ROWS,
    'central validation candidate IDs are not unique');
  const frozenDispositionCounts = countsBy(
    validationEntries.map(function (entry) { return entry.row.disposition; }),
    VALIDATION_DISPOSITIONS,
  );
  assert(frozenDispositionCounts.reportable === EXPECTED_REPORTABLE_ROWS
    && frozenDispositionCounts.needs_review === EXPECTED_NEEDS_REVIEW_ROWS
    && frozenDispositionCounts.not_reportable === EXPECTED_NOT_REPORTABLE_ROWS,
  'central validation disposition counts do not match frozen 101 reportable / 22 needs_review / 65 not_reportable');

  const centralManifestBytes = readRequired(CENTRAL_MANIFEST_PATH);
  const centralManifestSha = sha256(centralManifestBytes);
  inputSeals.set(CENTRAL_MANIFEST_PATH, centralManifestSha);
  const centralManifest = parsePrettyJson(CENTRAL_MANIFEST_PATH, centralManifestBytes);
  const centralManifestSeal = validateCentralManifest(
    centralManifest,
    centralManifestSha,
    validationBytes,
    validation.rows,
  );

  const threatModelBytes = readRequired(THREAT_MODEL_PATH);
  assert(threatModelBytes.at(-1) === 0x0a, THREAT_MODEL_PATH + ' must end with LF');
  assert(!threatModelBytes.includes(0x0d), THREAT_MODEL_PATH + ' must not contain CR bytes');
  const threatModelText = threatModelBytes.toString('utf8');
  assert(Buffer.from(threatModelText).equals(threatModelBytes), THREAT_MODEL_PATH + ' is not valid UTF-8');
  const threatModelLines = threatModelText.trimEnd().split('\n');
  assert(threatModelLines.at(-2) === 'Repository: ' + TARGET_REPOSITORY_ID,
    THREAT_MODEL_PATH + ' repository identity does not exactly match the immutable target ID');
  assert(threatModelLines.at(-1) === 'Version: ' + TARGET_COMMIT,
    THREAT_MODEL_PATH + ' version does not match the immutable target');
  const threatModelSha = sha256(threatModelBytes);
  inputSeals.set(THREAT_MODEL_PATH, threatModelSha);

  const ledgerInfo = inspectCentralAdoption(validationEntries, centralManifest);
  const eligibleEntries = validationEntries.filter(function (entry) {
    return ELIGIBLE_DISPOSITIONS.includes(entry.row.disposition);
  });
  assert(eligibleEntries.length === EXPECTED_ELIGIBLE_ROWS,
    'eligible central validation rows must equal frozen total ' + EXPECTED_ELIGIBLE_ROWS);
  assert(BATCHES.reduce(function (total, batch) { return total + batch.expectedEligibleRows; }, 0)
    === EXPECTED_ELIGIBLE_ROWS, 'configured attack-path batch counts do not sum to frozen eligible total');

  const outputMap = new Map();
  const ledgerSeals = new Map();
  const batchSeals = [];
  const stagedEntries = [];
  let attackReceiptsAlreadyPresent = 0;

  for (const batch of BATCHES) {
    const expected = eligibleEntries.filter(function (entry) {
      return entry.line >= batch.start && entry.line <= batch.end;
    });
    assert(expected.length === batch.expectedEligibleRows,
      batch.id + ' eligible row count mismatch: expected frozen ' + batch.expectedEligibleRows
      + '; got ' + expected.length);
    const attackBytes = readRequired(batch.attackPath);
    const attackSha = sha256(attackBytes);
    inputSeals.set(batch.attackPath, attackSha);
    const parsed = parseStrictJsonl(batch.attackPath, attackBytes);
    assert(parsed.rows.length === expected.length,
      batch.attackPath + ' row count mismatch: expected ' + expected.length + '; got ' + parsed.rows.length);

    const reportSeals = [];
    const expectedReportNames = [];
    parsed.rows.forEach(function (row, localIndex) {
      const source = expected[localIndex];
      const label = batch.attackPath + ':' + (localIndex + 1);
      exactKeys(row, ATTACK_KEYS, label);
      assertNoExcludedEvidenceReferences(row, label);
      assert(row.schema_version === 'deep-security-attack-path-staging-v1', label + '.schema_version mismatch');
      assert(row.event === 'centralized_attack_path_receipt', label + '.event mismatch');
      assert(row.candidate_id === source.row.candidate_id, label + '.candidate_id mismatch');
      assert(row.validation_line === source.line, label + '.validation_line mismatch');
      assert(row.source_validation_path === CENTRAL_VALIDATION_PATH, label + '.source_validation_path mismatch');
      assert(row.source_validation_stream_sha256 === validationSha,
        label + '.source_validation_stream_sha256 mismatch');
      assert(row.source_validation_row_sha256 === source.rawSha256,
        label + '.source_validation_row_sha256 mismatch');
      assert(row.central_validation_manifest_path === CENTRAL_MANIFEST_PATH,
        label + '.central_validation_manifest_path mismatch');
      assert(row.central_validation_manifest_sha256 === centralManifestSha,
        label + '.central_validation_manifest_sha256 mismatch');
      assert(row.threat_model_path === THREAT_MODEL_PATH, label + '.threat_model_path mismatch');
      assert(row.threat_model_sha256 === threatModelSha, label + '.threat_model_sha256 mismatch');
      assert(row.target === TARGET_COMMIT, label + '.target mismatch');
      assert(row.validation_disposition === source.row.disposition, label + '.validation_disposition mismatch');
      assert(row.title === source.canonical.title, label + '.title mismatch');
      assert(row.instance_key === source.canonical.instance_key, label + '.instance_key mismatch');
      assert(row.ledger_row_id === CENTRAL_VALIDATION_RUN_ID + ':' + row.candidate_id,
        label + '.ledger_row_id mismatch');
      validateAffectedLocations(row.affected_locations, label + '.affected_locations');
      assert(deepEqual(row.affected_locations, source.row.affected_locations),
        label + '.affected_locations must exactly equal current validation affected_locations');
      stringArray(row.attack_path_steps, label + '.attack_path_steps');
      assert(row.attack_path_steps.length > 0, label + '.attack_path_steps must not be empty');
      validateAttackFacts(row.attack_path_facts, label + '.attack_path_facts');
      stringArray(row.counterevidence, label + '.counterevidence');
      assert(deepEqual(row.counterevidence, row.attack_path_facts.counterevidence),
        label + '.counterevidence must equal attack_path_facts.counterevidence');
      validatePolicy(row, label);
      confidenceNumber(row.confidence, label + '.confidence');
      assert(row.confidence === row.attack_path_facts.confidence,
        label + '.confidence must equal attack_path_facts.confidence');
      stringArray(row.proof_gaps, label + '.proof_gaps');
      nonemptyString(row.rationale, label + '.rationale');

      const expectedReportPath = batch.reportsDir + '/' + row.candidate_id + '.md';
      assert(row.report_path === expectedReportPath, label + '.report_path mismatch');
      expectedReportNames.push(path.basename(expectedReportPath));
      assert(/^[a-f0-9]{64}$/.test(row.report_sha256), label + '.report_sha256 must be SHA-256');
      const reportBytes = readRequired(row.report_path);
      const reportSha = sha256(reportBytes);
      assert(reportSha === row.report_sha256, label + '.report_sha256 mismatch');
      validateVisibleReport(row.report_path, reportBytes, row);
      inputSeals.set(row.report_path, reportSha);
      reportSeals.push({
        candidate_id: row.candidate_id,
        path: row.report_path,
        bytes: reportBytes.length,
        sha256: reportSha,
      });

      const expectedLedgerPath = 'artifacts/05_findings/' + row.candidate_id + '/candidate_ledger.jsonl';
      assert(row.canonical_ledger_path === expectedLedgerPath, label + '.canonical_ledger_path mismatch');
      assert(/^[a-f0-9]{64}$/.test(row.canonical_ledger_sha256),
        label + '.canonical_ledger_sha256 must be SHA-256');
      const ledger = ledgerInfo.get(row.candidate_id);
      assert(ledger && ledger.centralBaseSha === row.canonical_ledger_sha256,
        label + '.canonical_ledger_sha256 must equal adopted central ledger hash');

      const canonicalReportPath = 'artifacts/05_findings/' + row.candidate_id + '/attack_path_analysis_report.md';
      outputMap.set(canonicalReportPath, reportBytes);

      const receipt = {
        schema_version: 'deep-security-scan-candidate-ledger-v1',
        event: 'canonical_attack_path_receipt',
        phase: 'attack_path',
        attack_path_run_id: ATTACK_PATH_RUN_ID,
        canonical_candidate_id: row.candidate_id,
        target: TARGET_COMMIT,
        target_tree: TARGET_TREE,
        source_validation_artifact: {
          path: CENTRAL_VALIDATION_PATH,
          line: source.line,
          row_sha256: source.rawSha256,
          file_sha256: validationSha,
          manifest_path: CENTRAL_MANIFEST_PATH,
          manifest_sha256: centralManifestSha,
        },
        source_attack_path_artifact: {
          path: batch.attackPath,
          batch_id: batch.id,
          line: localIndex + 1,
          row_sha256: sha256(parsed.rawLineBytes[localIndex]),
          file_sha256: attackSha,
          staged_report_path: row.report_path,
          staged_report_sha256: reportSha,
          canonical_report_path: canonicalReportPath,
        },
        attack_path_reportability_decision: row.final_policy_decision,
        calibrated_severity: row.calibrated_severity,
        final_priority: row.final_priority,
        confidence: row.confidence,
        affected_locations: row.affected_locations,
        source_attack_path: row,
        centralized_validation_performed: true,
        centralized_attack_path_analysis_performed: true,
      };
      const receiptBytes = jsonlRowBytes(receipt);
      const plannedLedgerBytes = Buffer.concat([ledger.centralBaseBytes, receiptBytes]);
      if (ledger.attackReceiptAlreadyPresent) {
        attackReceiptsAlreadyPresent += 1;
        const currentReceiptBytes = Buffer.from(ledger.parsed.rawLines.at(-1) + '\n');
        assert(currentReceiptBytes.equals(receiptBytes),
          ledger.path + ' existing marked attack-path receipt differs from the plan');
        assert(ledger.currentBytes.equals(plannedLedgerBytes),
          ledger.path + ' contains unexpected bytes around the attack-path receipt');
      }
      outputMap.set(ledger.path, plannedLedgerBytes);
      const plannedLedgerSha = sha256(plannedLedgerBytes);
      ledgerSeals.set(ledger.path, {
        preappendSha256: ledger.centralBaseSha,
        plannedSha256: plannedLedgerSha,
      });
      stagedEntries.push({
        batch,
        batchLine: localIndex + 1,
        source,
        row,
        rawLineBytes: parsed.rawLineBytes[localIndex],
        attackFileSha256: attackSha,
        reportBytes,
        reportSha,
        canonicalReportPath,
        ledgerPath: ledger.path,
        ledgerPreappendSha: ledger.centralBaseSha,
        ledgerPlannedSha: plannedLedgerSha,
        receiptRowSha: sha256(receiptBytes.subarray(0, receiptBytes.length - 1)),
      });
    });

    const reportsDirectory = asAbsolute(batch.reportsDir);
    assert(fs.existsSync(reportsDirectory), 'missing reports directory: ' + batch.reportsDir);
    assertSafeDirectoryAbsolute(reportsDirectory, batch.reportsDir);
    const actualReportNames = fs.readdirSync(reportsDirectory, { withFileTypes: true }).map(function (entry) {
      assert(entry.isFile() && !entry.isSymbolicLink(),
        batch.reportsDir + '/' + entry.name + ' must be a regular non-symlink file');
      assert(entry.name.endsWith('.md'), batch.reportsDir + ' contains a non-Markdown extra file: ' + entry.name);
      return entry.name;
    }).sort();
    const sortedExpectedReportNames = [...expectedReportNames].sort();
    assert(deepEqual(actualReportNames, sortedExpectedReportNames),
      batch.reportsDir + ' report inventory does not exactly match the eligible attack-path rows');

    batchSeals.push({
      batch_id: batch.id,
      canonical_lines: { start: batch.start, end: batch.end },
      expected_eligible_rows: expected.length,
      attack_path: { path: batch.attackPath, bytes: attackBytes.length, rows: parsed.rows.length, sha256: attackSha },
      reports: reportSeals,
    });
  }

  assert(stagedEntries.length === eligibleEntries.length, 'attack-path rows do not cover every eligible validation row');
  assert(new Set(stagedEntries.map(function (entry) { return entry.row.candidate_id; })).size === eligibleEntries.length,
    'attack-path candidate IDs are not unique');
  stagedEntries.forEach(function (entry, index) {
    assert(entry.row.candidate_id === eligibleEntries[index].row.candidate_id,
      'attack-path global order mismatch at row ' + (index + 1));
  });

  const streamPath = 'artifacts/attack_path_round06/attack_path.jsonl';
  const streamBytes = Buffer.from(stagedEntries.map(function (entry) {
    return entry.rawLineBytes.toString('utf8');
  }).join('\n') + '\n');
  const streamSha = sha256(streamBytes);
  outputMap.set(streamPath, streamBytes);

  const policyCounts = countsBy(stagedEntries.map(function (entry) { return entry.row.final_policy_decision; }),
    FINAL_POLICY_DECISIONS);
  const severityCounts = countsBy(stagedEntries.map(function (entry) { return entry.row.calibrated_severity; }),
    CALIBRATED_SEVERITIES);
  const priorityCounts = countsBy(stagedEntries.map(function (entry) { return entry.row.final_priority; }),
    FINAL_PRIORITIES);
  const validationDispositionCounts = countsBy(
    eligibleEntries.map(function (entry) { return entry.row.disposition; }),
    ELIGIBLE_DISPOSITIONS,
  );

  const summaryJsonPath = 'artifacts/attack_path_round06/attack_path_summary.json';
  const summaryJson = {
    schema_version: 'deep-security-attack-path-summary-v1',
    event: 'central_attack_path_assembly_summary',
    attack_path_run_id: ATTACK_PATH_RUN_ID,
    target: { commit: TARGET_COMMIT, tree: TARGET_TREE },
    source_validation: {
      path: CENTRAL_VALIDATION_PATH,
      sha256: validationSha,
      manifest_path: CENTRAL_MANIFEST_PATH,
      manifest_sha256: centralManifestSha,
      total_rows: validationEntries.length,
      disposition_counts: frozenDispositionCounts,
      eligible_rows: eligibleEntries.length,
      eligible_disposition_counts: validationDispositionCounts,
    },
    threat_model: { path: THREAT_MODEL_PATH, sha256: threatModelSha },
    results: {
      rows: stagedEntries.length,
      final_policy_counts: policyCounts,
      calibrated_severity_counts: severityCounts,
      final_priority_counts: priorityCounts,
    },
    ledger_plan: {
      canonical_attack_path_receipts: stagedEntries.length,
      preserves_historical_and_central_rows: true,
      marked_suffix_id: ATTACK_PATH_RUN_ID,
    },
    report_plan: {
      per_finding_reports: stagedEntries.length,
      scan_summary_path: 'artifacts/05_findings/attack_path_analysis_report.md',
    },
  };
  const summaryJsonBytes = jsonBytes(summaryJson);
  const summaryJsonSha = sha256(summaryJsonBytes);
  outputMap.set(summaryJsonPath, summaryJsonBytes);

  const scanReportPath = 'artifacts/05_findings/attack_path_analysis_report.md';
  const scanReportLines = [
    '# Central Attack-Path Analysis Report',
    '',
    '- Target commit: ' + TARGET_COMMIT,
    '- Source validation rows: ' + validationEntries.length,
    '- Rows entering attack-path analysis: ' + eligibleEntries.length,
    '- Final policy counts: ' + Object.entries(policyCounts).map(function (entry) { return entry[0] + '=' + entry[1]; }).join(', '),
    '- Calibrated severity counts: ' + Object.entries(severityCounts).map(function (entry) { return entry[0] + '=' + entry[1]; }).join(', '),
    '- Every reportable or needs_review validation row has one marked ledger receipt and one visible per-finding report.',
    '- Worker attack-path judgments are preserved; this assembler does not invent attack chains or reinterpret policy decisions.',
    '',
    '## Finding Index',
    '',
    '| Validation line | Candidate | Title | Validation disposition | Final policy | Severity | Priority | Report |',
    '|---:|---|---|---|---|---|---|---|',
    ...stagedEntries.map(function (entry) {
      const row = entry.row;
      const link = './' + row.candidate_id + '/attack_path_analysis_report.md';
      return '| ' + row.validation_line + ' | ' + markdownCell(row.candidate_id) + ' | '
        + markdownCell(row.title) + ' | ' + markdownCell(row.validation_disposition) + ' | '
        + markdownCell(row.final_policy_decision) + ' | ' + markdownCell(row.calibrated_severity) + ' | '
        + markdownCell(row.final_priority) + ' | [report](' + link + ') |';
    }),
    '',
  ];
  const scanReportBytes = Buffer.from(scanReportLines.join('\n'));
  const scanReportSha = sha256(scanReportBytes);
  outputMap.set(scanReportPath, scanReportBytes);

  const builderRelativePath = path.relative(SCAN_ROOT, SCRIPT_PATH).split(path.sep).join('/');
  assertSafeRegularFileAbsolute(SCRIPT_PATH, builderRelativePath);
  const builderBytes = fs.readFileSync(SCRIPT_PATH);
  const builderSha = sha256(builderBytes);
  inputSeals.set(builderRelativePath, builderSha);

  const manifestPath = 'artifacts/attack_path_round06/attack_path_manifest.json';
  const repositoryForManifest = { ...repositoryState };
  delete repositoryForManifest.rawPorcelain;
  const manifest = {
    schema_version: 'deep-security-attack-path-manifest-v1',
    event: 'central_attack_path_assembly_manifest',
    attack_path_run_id: ATTACK_PATH_RUN_ID,
    target: { commit: TARGET_COMMIT, tree: TARGET_TREE },
    safety_gate: {
      default_mode: 'check-only',
      write_requires_exact_manifest_sha256: true,
      central_validation_adoption_required: true,
      frozen_eligible_rows_required: EXPECTED_ELIGIBLE_ROWS,
      precentral_ledger_prefix_authentication_required: true,
      hidden_report_content_rejected: true,
      no_quarantine_path_references: true,
      no_preterminal_validation_draft_path_references: true,
      assembler_invents_attack_paths: false,
    },
    repository: repositoryForManifest,
    builder: { path: builderRelativePath, bytes: builderBytes.length, sha256: builderSha },
    canonical: { path: CANONICAL_PATH, bytes: canonicalBytes.length, rows: canonical.rows.length, sha256: CANONICAL_SHA256 },
    central_validation: {
      path: CENTRAL_VALIDATION_PATH,
      bytes: validationBytes.length,
      rows: validationEntries.length,
      sha256: validationSha,
      manifest: centralManifestSeal,
      adoption_verified_for_all_ledgers: true,
    },
    threat_model: { path: THREAT_MODEL_PATH, bytes: threatModelBytes.length, sha256: threatModelSha },
    batch_inputs: batchSeals,
    consolidated_outputs: {
      attack_path: { path: streamPath, bytes: streamBytes.length, rows: stagedEntries.length, sha256: streamSha },
      summary_json: { path: summaryJsonPath, bytes: summaryJsonBytes.length, sha256: summaryJsonSha },
      scan_report: { path: scanReportPath, bytes: scanReportBytes.length, sha256: scanReportSha },
    },
    finding_plans: stagedEntries.map(function (entry) {
      return {
        candidate_id: entry.row.candidate_id,
        validation_line: entry.row.validation_line,
        source_validation_row_sha256: entry.row.source_validation_row_sha256,
        source_attack_path_row_sha256: sha256(entry.rawLineBytes),
        staged_report_path: entry.row.report_path,
        staged_report_sha256: entry.reportSha,
        canonical_report_path: entry.canonicalReportPath,
        canonical_report_sha256: entry.reportSha,
        canonical_ledger_path: entry.ledgerPath,
        ledger_preappend_sha256: entry.ledgerPreappendSha,
        ledger_planned_sha256: entry.ledgerPlannedSha,
        receipt_row_sha256: entry.receiptRowSha,
      };
    }),
    invariants: {
      exact_eligible_validation_rows: eligibleEntries.length,
      exact_reportable_validation_rows: EXPECTED_REPORTABLE_ROWS,
      exact_needs_review_validation_rows: EXPECTED_NEEDS_REVIEW_ROWS,
      exact_not_reportable_validation_rows: EXPECTED_NOT_REPORTABLE_ROWS,
      exact_attack_path_rows: stagedEntries.length,
      exact_per_finding_reports: stagedEntries.length,
      all_eligible_rows_covered_once: true,
      exact_affected_locations_preserved: true,
      no_quarantine_path_references: true,
      no_preterminal_validation_draft_path_references: true,
      current_validation_row_hashes_verified: true,
      central_validation_adoption_verified: true,
      precentral_ledger_prefixes_authenticated: true,
      central_receipt_top_level_fields_verified: true,
      exact_threat_model_target_footer_verified: true,
      required_report_content_visible: true,
      historical_and_central_ledger_rows_preserved: true,
      canonical_attack_path_receipts_planned: stagedEntries.length,
    },
  };
  const manifestBytes = jsonBytes(manifest);
  const manifestSha = sha256(manifestBytes);
  outputMap.set(manifestPath, manifestBytes);

  assertNoOrphanTransactionFiles(outputMap);
  const existingManifestState = readOutputState(manifestPath);
  if (existingManifestState !== null) {
    assert(existingManifestState.bytes.equals(manifestBytes),
      'existing attack-path manifest differs from the current sealed plan');
    for (const [relativePath, plannedBytes] of outputMap) {
      const state = readOutputState(relativePath);
      assert(state !== null && state.bytes.equals(plannedBytes),
        'commit marker exists but output set is incomplete or inconsistent: ' + relativePath);
    }
  }

  const expectedOutputHashes = new Map([...outputMap.entries()].map(function (entry) {
    return [entry[0], sha256(entry[1])];
  }));
  const verificationLedgerSeals = new Map();
  for (const [candidateId, ledger] of ledgerInfo) {
    if (ledgerSeals.has(ledger.path)) {
      verificationLedgerSeals.set(ledger.path, ledgerSeals.get(ledger.path));
    } else {
      verificationLedgerSeals.set(ledger.path, {
        preappendSha256: ledger.centralBaseSha,
        plannedSha256: ledger.centralBaseSha,
      });
    }
    assert(canonicalMap.has(candidateId), 'ledger verification seal has unknown candidate: ' + candidateId);
  }
  assert(verificationLedgerSeals.size === TOTAL_CANONICAL_ROWS,
    'ledger verification seals do not cover all 188 central-adoption ledgers');
  const permittedStates = new Map();
  for (const [relativePath, plannedSha] of expectedOutputHashes) {
    if (ledgerSeals.has(relativePath)) {
      const seal = ledgerSeals.get(relativePath);
      permittedStates.set(relativePath, [seal.preappendSha256, seal.plannedSha256]);
    } else {
      permittedStates.set(relativePath, [null, plannedSha]);
    }
  }
  const wouldChangeFiles = [];
  for (const [relativePath, plannedBytes] of outputMap) {
    const state = readOutputState(relativePath);
    if (state === null || !state.bytes.equals(plannedBytes)) wouldChangeFiles.push(relativePath);
  }

  return {
    inputSeals,
    ledgerSeals: verificationLedgerSeals,
    outputMap,
    expectedOutputHashes,
    permittedStates,
    manifestSha,
    wouldChangeFiles,
    stdout: {
      mode: null,
      target: TARGET_COMMIT,
      source_validation_rows: validationEntries.length,
      source_validation_sha256: validationSha,
      central_validation_manifest_sha256: centralManifestSha,
      eligible_validation_rows: eligibleEntries.length,
      eligible_disposition_counts: validationDispositionCounts,
      batch_rows: Object.fromEntries(batchSeals.map(function (batch) {
        return [batch.batch_id, batch.attack_path.rows];
      })),
      attack_path_rows: stagedEntries.length,
      final_policy_counts: policyCounts,
      calibrated_severity_counts: severityCounts,
      final_priority_counts: priorityCounts,
      ledger_receipts_planned: stagedEntries.length,
      ledger_receipts_already_present: attackReceiptsAlreadyPresent,
      per_finding_reports_planned: stagedEntries.length,
      attack_path_stream_sha256: streamSha,
      summary_json_sha256: summaryJsonSha,
      scan_report_sha256: scanReportSha,
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

function verifyLedgersStable(plan) {
  for (const [relativePath, seal] of plan.ledgerSeals) {
    const currentSha = sha256(readRequired(relativePath));
    assert(currentSha === seal.preappendSha256 || currentSha === seal.plannedSha256,
      'canonical ledger changed: ' + relativePath);
  }
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const repositoryState = captureRepositoryState();
  const plan = buildPlan(repositoryState);
  plan.stdout.mode = args.mode;

  verifyInputsUnchanged(plan);
  verifyLedgersStable(plan);
  verifyRepositoryUnchanged(repositoryState);

  if (args.mode === 'write') {
    assert(args.manifestSha256 === plan.manifestSha,
      'write seal mismatch: supplied ' + args.manifestSha256 + '; current plan is ' + plan.manifestSha);
    verifyInputsUnchanged(plan);
    verifyLedgersStable(plan);
    verifyRepositoryUnchanged(repositoryState);
    writeTransaction(plan.outputMap, plan.expectedOutputHashes, plan.permittedStates, function () {
      verifyInputsUnchanged(plan);
      verifyLedgersStable(plan);
      verifyRepositoryUnchanged(repositoryState);
      const postPlan = buildPlan(repositoryState);
      assert(postPlan.manifestSha === plan.manifestSha, 'post-write manifest plan is not idempotent');
      assert(postPlan.wouldChangeFiles.length === 0,
        'post-write plan is not idempotent: ' + postPlan.wouldChangeFiles.join(', '));
    });
    plan.stdout.would_change_files = [];
    plan.stdout.ledger_receipts_already_present = plan.stdout.ledger_receipts_planned;
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
