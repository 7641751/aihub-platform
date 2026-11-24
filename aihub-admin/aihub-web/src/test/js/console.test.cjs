const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { test } = require('node:test');
const { runInNewContext } = require('node:vm');

const assets = join(__dirname, '../../main/resources/static/console');
const html = readFileSync(join(assets, 'index.html'), 'utf8');
const script = readFileSync(join(assets, 'console.js'), 'utf8');

// The console uses a small DOM surface. Run its real script with that surface and a stubbed fetch.
function element(tagName, hidden = false) {
  const listeners = new Map();
  const classes = new Set();
  const attributes = new Map();
  let text = '';
  return {
    tagName, hidden, disabled: false, children: [], value: '',
    classList: { toggle: (name, enabled) => enabled ? classes.add(name) : classes.delete(name) },
    hasClass: (name) => classes.has(name),
    addEventListener: (name, callback) => listeners.set(name, callback),
    setAttribute: (name, value) => attributes.set(name, String(value)),
    getAttribute: (name) => attributes.get(name),
    focus() { this.focused = true; },
    appendChild(child) { this.children.push(child); },
    dispatch(name) { listeners.get(name)({ preventDefault() {} }); },
    click() { if (!this.disabled) this.dispatch('click'); },
    reset() {},
    get textContent() { return text + this.children.map((child) => child.textContent).join(''); },
    set textContent(value) { text = value; this.children.length = 0; }
  };
}

function response(data, status = 200, code = 'OK', message = '') {
  return { ok: status < 400, status, json: async () => ({ data, code, message }) };
}

async function openConsole(role, probe, channels = [
  { id: 42, name: 'primary', provider: 'openai', baseUrl: 'https://example.test', status: 'ACTIVE' }
], handleRequest = (path) => { throw new Error('Unexpected request ' + path); }) {
  const elements = new Map(Array.from(html.matchAll(/<([a-z]+) [^>]*?\bid="([^"]+)"([^>]*)>/g),
    ([, tag, id, attributes]) => [id, element(tag, /\bhidden\b/.test(attributes))]));
  let token = 'test-token';
  const sessionStorage = {
    getItem: () => token,
    setItem: (_key, value) => { token = value; },
    removeItem: () => { token = null; }
  };
  const requests = [];
  const byId = (id) => elements.get(id);
  const document = {
    getElementById: byId,
    createElement: element,
    addEventListener: (_event, init) => init()
  };
  runInNewContext(script, {
    document, window: { sessionStorage },
    async fetch(path, options) {
      requests.push({ path, ...options });
      switch (path) {
        case '/api/auth/login': return response({ role: 'VIEWER', token: 'viewer-token' });
        case '/api/ping': return response({ role: token === 'viewer-token' ? 'VIEWER' : role, tenantId: 1 });
        case '/api/channels': return response(channels);
        case '/api/channels/42/probe': return probe();
        default: return handleRequest(path, options);
      }
    }
  });
  await nextTurn();
  return { byId, requests };
}

function nextTurn() {
  return new Promise((resolve) => setImmediate(resolve));
}

async function openLogs(respond) {
  const page = await openConsole('VIEWER', undefined, undefined, (path) => {
    assert.ok(path.startsWith('/api/logs?'), path);
    return respond(new URLSearchParams(path.split('?')[1]));
  });
  page.byId('tab-logs').click();
  return page;
}

for (const [zone, utc] of [['Asia/Shanghai', '08:00'], ['UTC', '16:00']]) {
  test(`log requests convert ${zone} local time to UTC`, async () => {
    const previous = process.env.TZ;
    process.env.TZ = zone;
    try {
      let query;
      const page = await openLogs((params) => {
        query = params;
        return response({ records: [] });
      });
      page.byId('logs-from').value = '2026-11-24T16:00';
      page.byId('logs-to').value = '2026-11-24T16:00';
      page.byId('logs-form').dispatch('submit');
      await nextTurn();
      assert.equal(query.get('from'), `2026-11-24T${utc}:00.000Z`);
      assert.equal(query.get('to'), query.get('from'));
      assert.match(page.byId('logs-timezone').textContent, new RegExp(zone));
      assert.equal(page.byId('logs-message').textContent, '该时间范围内没有请求');
    } finally {
      if (previous === undefined) delete process.env.TZ;
      else process.env.TZ = previous;
    }
  });
}

test('log results, errors and busy state belong to the latest query', async () => {
  const requests = [];
  const page = await openLogs(() => new Promise((resolve) => { requests.push(resolve); }));
  const form = page.byId('logs-form');
  const body = page.byId('logs-body');
  const message = page.byId('logs-message');
  const success = (requestId) => response({ records: [{ requestId, status: 'SUCCESS' }] });

  form.dispatch('submit');
  requests[0](success('previous'));
  await nextTurn();
  assert.match(body.textContent, /previous/);
  form.dispatch('submit');
  assert.equal(body.textContent, '');
  assert.match(message.textContent, /正在查询/);
  assert.equal(body.getAttribute('aria-busy'), 'true');
  form.dispatch('submit');
  requests[1](success('while pending'));
  await nextTurn();
  assert.equal(body.textContent, '');
  assert.equal(body.getAttribute('aria-busy'), 'true');

  form.dispatch('submit');
  requests[3](success('new result'));
  await nextTurn();
  assert.equal(body.getAttribute('aria-busy'), 'false');
  assert.match(body.textContent, /new result/);
  assert.match(message.textContent, /1 条/);
  requests[2](response(null, 500, 'INTERNAL_ERROR', 'old error'));
  await nextTurn();
  assert.match(body.textContent, /new result/);
  assert.match(message.textContent, /1 条/);
});

test('logs default to the last 24 hours and an error keeps the filters for retry', async () => {
  const queries = [];
  const page = await openLogs((params) => {
    queries.push(params);
    return queries.length === 1 ? response(null, 500, 'INTERNAL_ERROR', 'retry') : response({ records: [] });
  });
  const tenant = page.byId('logs-tenant-id');
  tenant.value = '42';
  page.byId('logs-form').dispatch('submit');
  await nextTurn();
  assert.match(page.byId('logs-message').textContent, /INTERNAL_ERROR/);
  assert.equal(tenant.value, '42');
  assert.equal(queries[0].get('tenantId'), '42');
  assert.equal(Date.parse(queries[0].get('to')) - Date.parse(queries[0].get('from')), 24 * 60 * 60 * 1000);
  page.byId('logs-form').dispatch('submit');
  await nextTurn();
  assert.equal(page.byId('logs-message').textContent, '该时间范围内没有请求');
});

test('a reversed log range focuses the start without issuing a request', async () => {
  const page = await openLogs(() => { throw new Error('invalid range was submitted'); });
  page.byId('logs-from').value = '2026-11-24T18:00';
  page.byId('logs-to').value = '2026-11-24T16:00';
  page.byId('logs-form').dispatch('submit');
  assert.equal(page.requests.some((request) => request.path.startsWith('/api/logs?')), false);
  assert.equal(page.byId('logs-from').focused, true);
  assert.match(page.byId('logs-message').textContent, /起始时间不能晚于结束时间/);
});

test('logging out invalidates an unfinished log query', async () => {
  let finish;
  const page = await openLogs(() => new Promise((resolve) => { finish = resolve; }));
  page.byId('logs-form').dispatch('submit');
  page.byId('logout-btn').click();
  page.byId('login-form').dispatch('submit');
  await nextTurn();
  finish(response({ records: [{ requestId: 'previous session' }] }));
  await nextTurn();
  assert.equal(page.byId('logs-body').textContent, '');
  assert.equal(page.byId('logs-message').textContent, '');
});

test('an administrator can probe one channel and see the upstream status, duration and message', async () => {
  let finishProbe;
  const page = await openConsole('ADMIN', () => new Promise((resolve) => { finishProbe = resolve; }));
  const row = page.byId('channels-body').children[0];
  assert.match(row.textContent, /primary/);
  assert.equal(page.byId('channel-form').hidden, false);
  const [button, result] = row.children.at(-1).children;
  assert.ok(button, 'the channel row has a probe button');
  assert.equal(button.textContent, '探活');

  button.click();
  button.click();
  assert.equal(button.disabled, true);
  assert.match(result.textContent, /探活中/);
  const probes = page.requests.filter((request) => request.path.endsWith('/probe'));
  assert.equal(probes.length, 1);
  assert.equal(probes[0].method, 'POST');
  assert.equal(probes[0].headers.Authorization, 'Bearer test-token');
  assert.equal(probes[0].body, undefined);

  finishProbe(response({ reachable: true, httpStatus: 200, latencyMs: 12, message: 'upstream ok' }));
  await nextTurn();
  assert.match(result.textContent, /可达.*HTTP 200.*12 ms.*upstream ok/);
  assert.equal(button.disabled, false);
});

test('a viewer can list channels and has no channel probe or create controls', async () => {
  const page = await openConsole('VIEWER');
  const row = page.byId('channels-body').children[0];
  assert.match(row.textContent, /primary/);
  assert.equal(row.children.length, 5);
  assert.equal(page.byId('channel-form').hidden, true);
  assert.equal(page.byId('key-form').hidden, true);
  assert.equal(page.byId('channel-actions-heading').hidden, true);
  page.byId('channel-form').dispatch('submit');
  page.byId('key-form').dispatch('submit');
  assert.ok(page.requests.every((request) => request.method === 'GET'));
});

test('reloading the channel list keeps the in-flight probe and its result', async () => {
  let finishProbe;
  const page = await openConsole('ADMIN', () => new Promise((resolve) => { finishProbe = resolve; }));
  const controls = () => page.byId('channels-body').children[0].children.at(-1).children;
  controls()[0].click();

  page.byId('tab-logs').click();
  page.byId('tab-channels').click();
  await nextTurn();
  const [reloadedButton, reloadedResult] = controls();
  assert.equal(reloadedButton.disabled, true);
  assert.match(reloadedResult.textContent, /探活中/);
  reloadedButton.click();
  assert.equal(page.requests.filter((request) => request.path.endsWith('/probe')).length, 1);

  finishProbe(response({ reachable: true, httpStatus: 200, latencyMs: 12 }));
  await nextTurn();
  assert.equal(reloadedButton.disabled, false);
  assert.match(reloadedResult.textContent, /可达.*HTTP 200/);
  page.byId('tab-channels').click();
  await nextTurn();
  assert.match(controls()[1].textContent, /可达.*HTTP 200/);
});

test('connection failure and API failure are shown separately, and the probe can be retried', async () => {
  const results = [
    response({ reachable: false, httpStatus: null, latencyMs: 25, message: 'connection failed' }),
    response(null, 404, 'NOT_FOUND', '渠道已删除')
  ];
  const page = await openConsole('ADMIN', () => results.shift());
  const [button, result] = page.byId('channels-body').children[0].children.at(-1).children;
  assert.ok(button, 'the channel row has a probe button');

  button.click();
  await nextTurn();
  assert.match(result.textContent, /不可达.*25 ms.*connection failed/);
  assert.doesNotMatch(result.textContent, /HTTP/);
  assert.equal(result.hasClass('error'), true);

  button.click();
  await nextTurn();
  assert.match(result.textContent, /NOT_FOUND.*渠道已删除/);
  assert.equal(button.disabled, false);
});

test('an empty channel list has an explicit empty state', async () => {
  const page = await openConsole('VIEWER', undefined, []);
  assert.equal(page.byId('channels-body').children.length, 0);
  assert.equal(page.byId('channels-message').textContent, '暂无渠道');
});

test('a probe from a previous session cannot sign out the current viewer', async () => {
  let finishProbe;
  const page = await openConsole('ADMIN', () => new Promise((resolve) => { finishProbe = resolve; }));
  const [button] = page.byId('channels-body').children[0].children.at(-1).children;
  button.click();
  page.byId('logout-btn').click();
  page.byId('login-form').dispatch('submit');
  await nextTurn();
  assert.equal(page.byId('app-view').hidden, false);
  assert.equal(page.byId('channel-form').hidden, true);

  finishProbe(response(null, 401, 'UNAUTHORIZED'));
  await nextTurn();
  assert.equal(page.byId('app-view').hidden, false);
  assert.match(page.byId('session-info').textContent, /VIEWER/);
});
