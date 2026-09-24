const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

// Exercise the real renderer with a minimal text-only DOM; no server or AI calls.
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/app.js'), 'utf8');
const renderer = source.slice(source.indexOf('  function renderAnalysis(result)'), source.indexOf('  byId("analyze").addEventListener'));
function render(performed, openQuestions, missingInformation) {
  const node = () => ({ children: [], textContent: '', append(...items) { this.children.push(...items); },
    replaceChildren(...items) { this.children = items; }, focus() {} });
  const nodes = new Map();
  const byId = id => { if (!nodes.has(id)) nodes.set(id, node()); return nodes.get(id); };
  const result = JSON.parse(fs.readFileSync(path.join(__dirname, '../resources/contracts/hypotheses-available.json'), 'utf8'));
  result.escalation.checks_performed = performed;
  if (openQuestions !== undefined) result.escalation.open_questions = openQuestions;
  if (missingInformation !== undefined) result.missing_information = missingInformation;
  vm.runInNewContext(renderer + '\nrenderAnalysis(result);', {
    result, byId, document: { createElement: node, createDocumentFragment: node },
    entry(parent, label, value) { parent.append({ textContent: label + ':' + value, children: [] }); }
  });
  function text(n) { return [n.textContent, ...n.children.map(text)].join('\n'); }
  return text(byId('result-content'));
}
test('empty performed checks describe missing information, not confirmed absence', () => {
  const text = render([]);
  assert.ok(text.includes('実施済み確認:実施済み確認の記載なし'));
  assert.ok(!text.includes('実施済み確認:なし'));
});
test('reported performed checks and server check ordering are preserved', () => {
  const text = render(['合成の確認記録']);
  assert.ok(text.includes('実施済み確認:合成の確認記録'));
  assert.ok(text.indexOf('確認ID:C1') < text.indexOf('確認ID:C2'));
});
test('empty open questions do not assert resolution', () => {
  const text = render([], []);
  assert.ok(text.includes('未解決の確認事項:今回の分析結果には記載されていません。解決済みであることを示すものではありません。'));
  assert.ok(!text.includes('未解決の確認事項:なし'));
});
test('empty missing information does not assert completeness', () => {
  const text = render([], [], []);
  assert.ok(text.includes('今回の分析結果では追加情報が挙げられていません。情報が十分であることを保証するものではありません。'));
});
test('nonempty questions and missing information remain unchanged', () => {
  const text = render([], ['時刻の対応'], [{ item: 'タイムゾーン', reason: '時刻の照合' }]);
  assert.ok(text.includes('未解決の確認事項:時刻の対応'));
  assert.ok(text.includes('項目:タイムゾーン'));
  assert.ok(text.includes('必要な理由:時刻の照合'));
  assert.ok(!text.includes('今回の分析結果'));
});
