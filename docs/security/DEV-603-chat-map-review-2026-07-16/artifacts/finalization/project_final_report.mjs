#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const source = path.join(here, 'diagnostic_security_review_round06_final.md');
const destination = path.resolve(here, '../../report.md');
const bytes = fs.readFileSync(source);
const text = bytes.toString('utf8');

if (!Buffer.from(text).equals(bytes)) throw new Error('final report is not valid UTF-8');
if (!bytes.length || bytes.at(-1) !== 0x0a) throw new Error('final report must end with LF');
if (/ATTACK_SUMMARY_TO_REPLACE|PREFINAL|TODO|1차 마감 초안/.test(text)) {
  throw new Error('final report still contains a pre-final marker');
}

fs.writeFileSync(destination, bytes);
if (!fs.readFileSync(destination).equals(bytes)) throw new Error('report.md projection mismatch');

console.log(JSON.stringify({ source, destination, bytes: bytes.length }));
