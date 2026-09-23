#!/usr/bin/env node
// ============================================================================
// 文档-代码一致性校验（goal prompt §1 B-U3/B-U4 验收物）
//
// 校验四件事：
//   1) API.md §3.x 表格声明的端点集合 == Controller 源码实际端点（method + path）
//   2) SPEC.md §11 表格声明的端点集合 == 同上
//   3) API.md 各小节标题里声明的数量 == 紧随其后表格的数据行数
//   4) API.md §1.6 错误码表覆盖 ErrorCode 全部常量；§1.7 枚举表 sendStatus 覆盖全部取值
//
// 用法：node scripts/check-api-md.mjs        # 有差异则退出码 1
//      node scripts/check-api-md.mjs -v     # 额外打印通过项明细
// ============================================================================
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const VERBOSE = process.argv.includes('-v');
const problems = [];
const notes = [];

const norm = (p) => p.replace(/\{[^}]*\}/g, '{}').replace(/\/+$/, '') || '/';
const key = (m, p) => `${m} ${norm(p)}`;

// ---------------------------------------------------------------- 源码端点
const VERB = { Get: 'GET', Post: 'POST', Put: 'PUT', Delete: 'DELETE', Patch: 'PATCH' };

function walkControllers(dir, out = []) {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) walkControllers(p, out);
    else if (p.endsWith('Controller.java')) out.push(p);
  }
  return out;
}

function codeEndpoints() {
  const map = new Map();
  for (const file of walkControllers(join(ROOT, 'src/main/java'))) {
    const src = readFileSync(file, 'utf8');
    const base = /@RequestMapping\(\s*(?:value\s*=\s*)?["'{]([^"'}]*)["'}]/.exec(src);
    const prefix = base ? base[1] : '';
    for (const line of src.split(/\r?\n/)) {
      const m = /@(Get|Post|Put|Delete|Patch)Mapping\s*(?:\(\s*(?:value\s*=\s*)?)?(\{[^}]*\}|"[^"]*")?/.exec(line);
      if (!m) continue;
      const verb = VERB[m[1]];
      const raw = !m[2] ? [''] : m[2].startsWith('{') ? [...m[2].matchAll(/"([^"]*)"/g)].map((x) => x[1]) : [m[2].replace(/"/g, '')];
      for (const r of raw) {
        const full = (prefix + r).replace(/\/{2,}/g, '/') || '/';
        const k = key(verb, full);
        if (map.has(k)) problems.push(`源码重复映射：${k}（${map.get(k).file} 与 ${file.replace(/\\/g, '/')}）`);
        map.set(k, { verb, path: full, file: file.replace(/\\/g, '/') });
      }
    }
  }
  return map;
}

// ---------------------------------------------------------------- 文档表格
/** 展开 `GET/POST/PUT` 与 `/api/admin/college[/{id}]` 这类简写 */
function expand(methodsCell, pathCell) {
  const methods = methodsCell.split('/').map((s) => s.trim()).filter(Boolean);
  let paths = [''];
  for (const part of pathCell.split(/(\[[^\]]*\])/).filter(Boolean)) {
    if (part.startsWith('[')) {
      const inner = part.slice(1, -1);
      paths = paths.flatMap((p) => [p, p + inner]);
    } else {
      paths = paths.map((p) => p + part);
    }
  }
  return methods.flatMap((m) => paths.map((p) => ({ m, p: p.replace(/\s/g, '') })));
}

function docEndpoints(file, sectionPattern, stopPattern) {
  const lines = readFileSync(join(ROOT, file), 'utf8').split(/\r?\n/);
  const map = new Map();
  let inSection = false;
  for (const line of lines) {
    if (sectionPattern.test(line)) inSection = true;
    else if (inSection && stopPattern && stopPattern.test(line)) inSection = false;
    if (!inSection) continue;
    const cells = line.split('|').map((s) => s.trim());
    if (cells.length < 4) continue;
    const [, methodCell, pathCell] = cells;
    if (!/^`?\/api/.test(pathCell.replace(/`/g, ''))) continue;
    for (const { m, p } of expand(methodCell.replace(/`/g, ''), pathCell.replace(/`/g, ''))) {
      map.set(key(m, p), { verb: m, path: p, file });
    }
  }
  return map;
}

function compare(label, code, doc) {
  const missing = [...code.keys()].filter((k) => !doc.has(k)).sort();
  const extra = [...doc.keys()].filter((k) => !code.has(k)).sort();
  if (missing.length) problems.push(`${label} 缺少 ${missing.length} 个端点（代码有、文档无）：\n    ` + missing.join('\n    '));
  if (extra.length) problems.push(`${label} 多出 ${extra.length} 个端点（文档有、代码无）：\n    ` + extra.join('\n    '));
  if (!missing.length && !extra.length) notes.push(`${label} 端点集合与代码一致（${doc.size} 个）`);
  return { missing, extra };
}

// -------------------------------------------------- API.md 标题计数 vs 行数
function headingCounts() {
  const lines = readFileSync(join(ROOT, 'API.md'), 'utf8').split(/\r?\n/);
  for (let i = 0; i < lines.length; i++) {
    if (!/^(#{3}\s*3\.\d+.*|\*\*角色与权限管理.*)$/.test(lines[i])) continue;
    // 声明数 = 标题里最后一对括号内所有数字之和（如「教师端 5 + 复核端 4」= 9）
    const group = /（([^（）]*)）\s*\**\s*$/.exec(lines[i].replace(/\s*$/, ''));
    if (!group) continue;
    // 剔除 BE-2 / §3.11 这类编号，只留计数
    const cleaned = group[1].replace(/BE-\d+/g, '').replace(/§[\d.]+/g, '');
    const declared = [...cleaned.matchAll(/(\d+)/g)].map((m) => Number(m[1]));
    if (!declared.length) continue;
    // 只数标题后第一张连续表格（子表另起标题/空行分隔）
    let rows = 0;
    let started = false;
    for (let j = i + 1; j < lines.length; j++) {
      const isTable = /^\|/.test(lines[j]);
      if (isTable) {
        started = true;
        if (!/^\|\s*-+/.test(lines[j]) && !/^\|\s*方法\s*\|/.test(lines[j])) rows++;
      } else if (started) break;
      else if (/^#{2,3}\s/.test(lines[j])) break;
    }
    const sum = declared.reduce((a, b) => a + b, 0);
    if (sum !== rows) problems.push(`API.md 标题计数不符：第 ${i + 1} 行「${lines[i].trim()}」声明 ${sum}，表格 ${rows} 行`);
    else notes.push(`API.md 标题计数一致：第 ${i + 1} 行（${sum}）`);
  }
}

// ------------------------------------------------- 错误码表 / 枚举表覆盖检查
function errorCodeCoverage() {
  const java = readFileSync(join(ROOT, 'src/main/java/com/tian/textbook/common/error/ErrorCode.java'), 'utf8');
  const codes = [...java.matchAll(/^\s{4}([A-Z][A-Z0-9_]*)\("([A-Z0-9_]+)"/gm)].map((m) => m[2]);
  const api = readFileSync(join(ROOT, 'API.md'), 'utf8');
  const table = api.slice(api.indexOf('### 1.6'), api.indexOf('### 1.7'));
  const missing = codes.filter((c) => c !== '0' && !table.includes('`' + c + '`'));
  if (missing.length) problems.push(`API.md §1.6 错误码表缺 ${missing.length} 个 ErrorCode 常量：${missing.join(', ')}`);
  else notes.push(`API.md §1.6 覆盖全部 ${codes.length - 1} 个 ErrorCode 常量`);
}

function enumCoverage() {
  const svc = readFileSync(join(ROOT, 'src/main/java/com/tian/textbook/notify/service/NotifyService.java'), 'utf8');
  const values = [...svc.matchAll(/SEND_STATUS_[A-Z_]+ = "([a-z_]+)"/g)].map((m) => m[1]);
  const api = readFileSync(join(ROOT, 'API.md'), 'utf8');
  const row = api.split(/\r?\n/).find((l) => l.includes('发送状态 `sendStatus`')) || '';
  const missing = [...new Set(values)].filter((v) => !row.includes('`' + v + '`'));
  if (missing.length) problems.push(`API.md §1.7 发送状态枚举缺 ${missing.join(', ')}`);
  else notes.push('API.md §1.7 发送状态枚举覆盖全部取值');
}

// ------------------------------------------------------------------ 主流程
const code = codeEndpoints();
compare('API.md', code, docEndpoints('API.md', /^### 3\.\d/, /^## 4\./));
compare('SPEC.md §11', code, docEndpoints('SPEC.md', /^## 11\./, /^## 12\./));
headingCounts();
errorCodeCoverage();
enumCoverage();

if (VERBOSE) for (const n of notes) console.log('  ok  ' + n);
console.log(`\n源码端点：${code.size} 个`);
if (problems.length) {
  console.log(`\n发现 ${problems.length} 处不一致：\n`);
  problems.forEach((p, i) => console.log(`${i + 1}. ${p}\n`));
  process.exit(1);
}
console.log('全部一致 ✓');
