const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.join(__dirname, '../../main/resources/static');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const source = fs.readFileSync(path.join(root, 'app.js'), 'utf8');
const fields = { occurred_at: 'occurred-at', environment: 'environment', ongoing_status: 'ongoing-status', destination: 'destination' };

// Run the complete app and real event handlers with a text-only DOM and fake HTTP.
function app(context) {
  const node = id => ({ id, value: '', textContent: '', children: [], handlers: {},
    classList: { toggle() {} }, setAttribute() {}, reset() {}, focus() {},
    addEventListener(name, handler) { this.handlers[name] = handler; },
    append(...items) { this.children.push(...items); }, replaceChildren(...items) { this.children = items; }
  });
  const nodes = new Map([...html.matchAll(/\bid="([^"]+)"/g)].map(m => [m[1], node(m[1])]));
  const window = node('window');
  const requests = [];
  vm.runInNewContext(source, {
    document: { getElementById: id => { assert.ok(nodes.has(id), id); return nodes.get(id); }, createElement: () => node(''), createDocumentFragment: () => node('') },
    window, AbortController, setTimeout, clearTimeout,
    fetch: async (url, options) => {
      requests.push({ url, options });
      if (options.method === 'DELETE') return {};
      return { ok: true, json: async () => ({ preview_id: '12345678-1234-4234-8234-123456789abc',
        expires_at: '2030-01-01T00:00:00Z', destination: 'Stub', purpose: '合成テスト', warnings: [], log_line_ids: [],
        masked_input: { symptom: '合成申告', log: '', log_status: '未取得', context: {
          occurred_at: '不明', environment: '不明', ongoing_status: '不明', destination: '不明',
          impact: '不明', recent_changes: '不明', checks_performed: '不明', ...context } }
      }) };
    }
  });
  const get = id => nodes.get(id);
  get('symptom').value = '本番で10時に発生、継続中、引き継ぎ先は運用';
  get('log-status').value = '未取得';
  return { get, requests, window,
    fill(values) { for (const [key, value] of Object.entries(values)) get(fields[key]).value = value; },
    submit: () => get('preview-form').handlers.submit({ preventDefault() {} }) };
}

for (const [name, values] of Object.entries({
  all: { occurred_at: ' 2026-09-24 10:00 JST\n', environment: '検証', ongoing_status: '解消済み', destination: ' 運用窓口 ' },
  empty: { occurred_at: '', environment: '', ongoing_status: '', destination: '' },
  partial: { occurred_at: '', environment: '開発', ongoing_status: '', destination: '運用窓口' },
  unknown: { occurred_at: '不明', environment: '不明', ongoing_status: '不明', destination: '不明' }
})) {
  test(`${name}: exact context is sent and server preview is displayed`, async () => {
    const expected = Object.fromEntries(Object.entries(values).map(([k, v]) => [k, v || '不明']));
    const a = app(expected); a.fill(values); await a.submit();
    assert.equal(a.requests.length, 1);
    assert.equal(a.requests[0].url, '/api/previews');
    assert.deepEqual(JSON.parse(a.requests[0].options.body).context, values);
    const rendered = a.get('context').children.filter((_, i) => i % 2).map(n => n.textContent);
    assert.deepEqual(rendered, [expected.occurred_at, expected.environment, '不明', expected.ongoing_status, '不明', '不明', expected.destination]);
    assert.equal(a.get('preview').hidden, false);
  });
}

test('preview uses masked server values rather than unmasked form data', async () => {
  const a = app({ destination: '[MASKED_1]' });
  a.fill({ destination: 'person@example.test' }); await a.submit();
  assert.equal(a.get('context').children.at(-1).textContent, '[MASKED_1]');
  assert.equal(JSON.parse(a.requests[0].options.body).context.destination, 'person@example.test');
});

test('select values exactly match existing API choices', () => {
  for (const [id, expected] of Object.entries({ environment: ['', '本番', '検証', '開発', '不明'], 'ongoing-status': ['', '継続中', '解消済み', '不明'] })) {
    const select = html.match(new RegExp(`<select id="${id}"[^>]*>(.*?)</select>`))[1];
    const options = [...select.matchAll(/<option(?: value="([^"]*)")?>(.*?)<\/option>/g)].map(m => m[1] ?? m[2]);
    assert.deepEqual(options, expected);
  }
});

for (const [field, max] of [['occurred_at', 100], ['destination', 200]]) {
  test(`${field}: Unicode boundary accepted, over-limit blocked without truncation`, async () => {
    const a = app(); a.fill({ [field]: '😀'.repeat(max) }); await a.submit();
    assert.equal(JSON.parse(a.requests[0].options.body).context[field], '😀'.repeat(max));
    a.fill({ [field]: '😀'.repeat(max + 1) }); await a.submit();
    assert.equal(a.requests.filter(r => r.options.method === 'POST').length, 1);
    assert.equal(a.get(fields[field]).value, '😀'.repeat(max + 1));
    assert.equal(a.get('error').hidden, false);
  });
}

test('editing context invalidates preview; clear and page lifecycle erase all four values', async () => {
  const a = app(); await a.submit();
  a.fill({ environment: '本番' }); a.get('preview-form').handlers.input();
  assert.equal(a.get('preview').hidden, true);
  assert.equal(a.get('analyze').disabled, true);
  assert.ok(a.requests.some(r => r.options.method === 'DELETE'));
  for (const reset of [a.get('clear').handlers.click, a.window.handlers.pagehide, a.window.handlers.pageshow]) {
    a.fill({ occurred_at: '日時', environment: '本番', ongoing_status: '継続中', destination: '窓口' });
    reset();
    for (const id of Object.values(fields)) assert.equal(a.get(id).value, '');
  }
});
