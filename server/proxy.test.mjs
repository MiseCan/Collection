import test from 'node:test';
import assert from 'node:assert/strict';
import { createProxyServer } from './proxy.mjs';

const apiKey = 'server-only-fake-key';
const clientToken = 'unit-test-client-token-24-characters';
const validPayload = {
  model: 'deepseek-flash', stream: false, max_tokens: 128,
  response_format: { type: 'json_object' },
  messages: [{ role: 'system', content: 'Return JSON title.' }, { role: 'user', content: [{ type: 'text', text: '原始资料' }] }],
};
const completion = () => new Response(JSON.stringify({ choices: [{ finish_reason: 'stop', message: { content: JSON.stringify({ title: '测试资料' }) } }] }), { status: 200 });

async function withProxy(options, fn) {
  const server = createProxyServer({ apiKey, clientToken, ...options });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}/chat/completions`;
  const post = (body = validPayload, token = clientToken) => fetch(url, {
    method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` }, body: JSON.stringify(body),
  });
  try { await fn(post, url); }
  finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
}

test('startup requires real configuration rather than example placeholders', () => {
  assert.throws(() => createProxyServer(), /DEEPSEEK_API_KEY/);
  assert.throws(() => createProxyServer({ apiKey, clientToken: 'short' }), /CLIENT_TOKEN/);
  assert.throws(() => createProxyServer({ apiKey: 'replace_with_key', clientToken }), /DEEPSEEK_API_KEY/);
});

test('unauthorized request never reaches the upstream', async () => {
  let called = false;
  await withProxy({ fetchImpl: async () => { called = true; return completion(); } }, async post => {
    const response = await post(validPayload, 'wrong');
    assert.equal(response.status, 401);
    assert.equal(called, false);
    assert.equal((await response.json()).error.message, 'unauthorized');
  });
});

test('rejects disallowed models, malformed content and image URLs', async () => {
  let called = false;
  await withProxy({ fetchImpl: async () => { called = true; return completion(); } }, async post => {
    assert.equal((await post({ ...validPayload, model: 'unapproved-model' })).status, 400);
    assert.equal((await post({ ...validPayload, messages: [] })).status, 400);
    const image = { role: 'user', content: [{ type: 'image_url', image_url: { url: 'https://private.example/image' } }] };
    assert.equal((await post({ ...validPayload, messages: [image] })).status, 400);
    assert.equal(called, false);
  });
});

test('forwards validated text and inline images with server-only credentials', async () => {
  let captured;
  await withProxy({ fetchImpl: async (url, options) => { captured = { url, options }; return completion(); } }, async post => {
    const payload = structuredClone(validPayload);
    payload.messages[1].content.push({ type: 'image_url', image_url: { url: 'data:image/jpeg;base64,/9j/AA==', detail: 'auto' } });
    payload.untrusted_upstream_url = 'https://attacker.example';
    const response = await post(payload);
    assert.equal(response.status, 200);
    const result = await response.json();
    assert.equal(JSON.parse(result.choices[0].message.content).title, '测试资料');
    assert.equal(captured.url, 'https://api.deepseek.com/chat/completions');
    assert.equal(captured.options.headers.authorization, `Bearer ${apiKey}`);
    assert.notEqual(captured.options.headers.authorization, `Bearer ${clientToken}`);
    const forwarded = JSON.parse(captured.options.body);
    assert.equal(forwarded.untrusted_upstream_url, undefined);
    assert.equal(forwarded.response_format.type, 'json_object');
    assert.equal(forwarded.messages[1].content[1].type, 'image_url');
    assert.equal(forwarded.messages[1].content[1].image_url.detail, 'original');
  });
});

test('upstream diagnostic body and server key never reach the client', async () => {
  await withProxy({ fetchImpl: async () => new Response(`secret: ${apiKey}`, { status: 401 }) }, async post => {
    const response = await post();
    assert.equal(response.status, 502);
    const text = await response.text();
    assert.equal(text.includes(apiKey), false);
    assert.equal(text.includes('secret:'), false);
  });
});

test('rejects invalid or incomplete upstream JSON output', async () => {
  await withProxy({ fetchImpl: async () => new Response(JSON.stringify({ choices: [{ finish_reason: 'length', message: { content: '{}' } }] })) }, async post => {
    const response = await post();
    assert.equal(response.status, 502);
    assert.equal((await response.json()).error.message, 'upstream_invalid_json_output');
  });
});

test('request body cap applies before contacting upstream', async () => {
  let called = false;
  await withProxy({ maxBodyBytes: 128, fetchImpl: async () => { called = true; return completion(); } }, async post => {
    assert.equal((await post()).status, 413);
    assert.equal(called, false);
  });
});

test('per-client rate limit returns bounded Retry-After', async () => {
  await withProxy({ requestsPerMinute: 1, fetchImpl: async () => completion() }, async post => {
    assert.equal((await post()).status, 200);
    const response = await post();
    assert.equal(response.status, 429);
    assert.equal(response.headers.get('retry-after'), '30');
  });
});

test('upstream timeout aborts a hanging request', async () => {
  await withProxy({ upstreamTimeoutMs: 50, fetchImpl: async (_url, { signal }) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true });
  }) }, async post => {
    const response = await post();
    assert.equal(response.status, 504);
    assert.equal((await response.json()).error.message, 'upstream_timeout');
  });
});

test('single upstream worker rejects excess queued requests', async () => {
  let release;
  let started;
  const ready = new Promise(resolve => { started = resolve; });
  await withProxy({ queueLimit: 0, fetchImpl: async () => {
    started();
    await new Promise(resolve => { release = resolve; });
    return completion();
  } }, async post => {
    const first = post();
    await ready;
    assert.equal((await post()).status, 503);
    release();
    assert.equal((await first).status, 200);
  });
});
