import { createServer } from 'node:http';
import { createHash, timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';

const MAX_BODY_BYTES = 12 * 1024 * 1024;
const MAX_UPSTREAM_BYTES = 2 * 1024 * 1024;

class HttpFailure extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

function authorized(header, token) {
  if (typeof header !== 'string' || !header.startsWith('Bearer ')) return false;
  const hash = value => createHash('sha256').update(value).digest();
  return timingSafeEqual(hash(header.slice(7)), hash(token));
}

function send(res, status, message, extraHeaders = {}) {
  if (res.destroyed || res.writableEnded) return;
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
    ...extraHeaders,
  });
  res.end(JSON.stringify({ error: { message } }));
}

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    const onData = chunk => {
      size += chunk.length;
      if (size > limit) {
        req.removeListener('data', onData);
        req.resume();
        reject(new HttpFailure(413, 'request_too_large'));
      } else chunks.push(chunk);
    };
    req.on('data', onData);
    req.once('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.once('error', () => reject(new HttpFailure(400, 'request_unreadable')));
    req.once('aborted', () => reject(new HttpFailure(400, 'request_aborted')));
  });
}

function validatePayload(value, models) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new HttpFailure(400, 'invalid_json_object');
  if (!models.has(value.model)) throw new HttpFailure(400, 'model_not_allowed');
  if (!Array.isArray(value.messages) || value.messages.length < 1 || value.messages.length > 100) throw new HttpFailure(400, 'invalid_messages');
  let images = 0;
  const messages = value.messages.map(message => {
    if (!message || !['system', 'user', 'assistant'].includes(message.role)) throw new HttpFailure(400, 'invalid_message_role');
    if (typeof message.content === 'string' && message.content.trim()) return { role: message.role, content: message.content };
    if (!Array.isArray(message.content) || message.content.length < 1 || message.content.length > 100) throw new HttpFailure(400, 'invalid_message_content');
    const content = message.content.map(part => {
      if (part?.type === 'text' && typeof part.text === 'string') return { type: 'text', text: part.text };
      if (part?.type === 'image_url' && message.role === 'user' && typeof part.image_url?.url === 'string') {
        // Only inline images; external URLs and Files API references are not part of this app's protocol.
        if (!/^data:image\/(?:jpeg|png|gif|webp);base64,[A-Za-z0-9+/]+={0,2}$/.test(part.image_url.url)) throw new HttpFailure(400, 'invalid_inline_image');
        if (++images > 8) throw new HttpFailure(400, 'too_many_images');
        return { type: 'image_url', image_url: { url: part.image_url.url, detail: 'original' } };
      }
      throw new HttpFailure(400, 'unsupported_content_part');
    });
    return { role: message.role, content };
  });
  const maxTokens = value.max_tokens ?? 128;
  if (!Number.isInteger(maxTokens) || maxTokens < 1 || maxTokens > 128) throw new HttpFailure(400, 'invalid_max_tokens');
  if (value.stream === true) throw new HttpFailure(400, 'streaming_not_supported');
  return {
    model: value.model, messages, max_tokens: maxTokens,
    stream: false, thinking: { type: 'disabled' }, response_format: { type: 'json_object' },
  };
}

async function readUpstream(response) {
  if (!response.body) throw new HttpFailure(502, 'upstream_empty_response');
  const reader = response.body.getReader();
  const chunks = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAX_UPSTREAM_BYTES) throw new HttpFailure(502, 'upstream_response_too_large');
      chunks.push(Buffer.from(value));
    }
  } finally { await reader.cancel().catch(() => {}); }
  const text = Buffer.concat(chunks).toString('utf8');
  let envelope;
  let result;
  try {
    envelope = JSON.parse(text);
    const choice = envelope.choices?.[0];
    if (choice?.finish_reason !== 'stop' || typeof choice.message?.content !== 'string') throw new Error();
    result = JSON.parse(choice.message.content);
    if (typeof result.title !== 'string' || !result.title.trim() || [...result.title.trim()].length > 30) throw new Error();
  } catch { throw new HttpFailure(502, 'upstream_invalid_json_output'); }
  // Return only the fields the Android client needs. Never relay upstream diagnostics or reasoning.
  return JSON.stringify({ choices: [{ finish_reason: 'stop', message: { role: 'assistant', content: JSON.stringify({ title: result.title.trim() }) } }] });
}

/** Small single-token proxy. For multi-user publication use real user accounts and per-user quotas. */
export function createProxyServer({
  apiKey,
  clientToken,
  models = ['deepseek-flash'],
  fetchImpl = globalThis.fetch,
  maxBodyBytes = MAX_BODY_BYTES,
  upstreamTimeoutMs = 90_000,
  queueTimeoutMs = 15_000,
  queueLimit = 4,
  requestsPerMinute = 12,
} = {}) {
  if (typeof apiKey !== 'string' || !apiKey.trim() || apiKey.startsWith('replace_')) throw new Error('Set DEEPSEEK_API_KEY before starting');
  if (typeof clientToken !== 'string' || clientToken.length < 24 || clientToken.startsWith('replace_')) throw new Error('Set a random CLIENT_TOKEN of at least 24 characters before starting');
  const allowedModels = new Set(models);
  if (allowedModels.size < 1 || [...allowedModels].some(model => typeof model !== 'string' || !model.trim())) throw new Error('ALLOWED_MODELS is empty or invalid');
  const bodyLimit = Math.max(1, Math.min(MAX_BODY_BYTES, maxBodyBytes));
  const upstreamLimit = Math.max(50, Math.min(120_000, upstreamTimeoutMs));
  const waitLimit = Math.max(50, Math.min(30_000, queueTimeoutMs));
  const rateLimit = Math.max(1, Math.min(120, requestsPerMinute));
  const waitingLimit = Math.max(0, Math.min(8, queueLimit));
  const rates = new Map();
  const queue = [];
  let active = false;

  function takeSlot(signal) {
    if (!active) { active = true; return Promise.resolve(); }
    if (queue.length >= waitingLimit) return Promise.reject(new HttpFailure(503, 'proxy_queue_full'));
    return new Promise((resolve, reject) => {
      const remove = () => { const index = queue.indexOf(job); if (index !== -1) queue.splice(index, 1); };
      const cancel = () => { clearTimeout(timer); remove(); reject(new HttpFailure(499, 'client_disconnected')); };
      const timer = setTimeout(() => {
        remove(); signal.removeEventListener('abort', cancel); reject(new HttpFailure(503, 'proxy_queue_timeout'));
      }, waitLimit);
      const job = () => { clearTimeout(timer); signal.removeEventListener('abort', cancel); resolve(); };
      queue.push(job);
      signal.addEventListener('abort', cancel, { once: true });
      if (signal.aborted) cancel();
    });
  }
  function releaseSlot() { const next = queue.shift(); if (next) next(); else active = false; }

  const server = createServer(async (req, res) => {
    let acquired = false;
    const controller = new AbortController();
    res.once('close', () => { if (!res.writableEnded) controller.abort(); });
    try {
      if (req.method !== 'POST' || req.url !== '/chat/completions') { req.resume(); return send(res, 404, 'not_found'); }
      if (!authorized(req.headers.authorization, clientToken)) { req.resume(); return send(res, 401, 'unauthorized'); }
      const now = Date.now();
      for (const [key, rate] of rates) if (rate.until <= now) rates.delete(key);
      const address = req.socket.remoteAddress || 'unknown';
      // Do not trust X-Forwarded-For from the caller. A reverse proxy's combined quota is intentional.
      const rate = rates.get(address) || { count: 0, until: now + 60_000 };
      if (rates.size >= 4096 && !rates.has(address)) { req.resume(); return send(res, 503, 'proxy_busy'); }
      rates.set(address, rate);
      if (++rate.count > rateLimit) { req.resume(); return send(res, 429, 'proxy_rate_limit', { 'retry-after': '30' }); }
      if (!req.headers['content-type']?.toLowerCase().startsWith('application/json')) { req.resume(); return send(res, 415, 'json_required'); }
      const declared = Number(req.headers['content-length']);
      if (Number.isFinite(declared) && declared > bodyLimit) { req.resume(); return send(res, 413, 'request_too_large'); }
      let payload;
      try { payload = JSON.parse(await readBody(req, bodyLimit)); }
      catch (error) { if (error instanceof HttpFailure) throw error; throw new HttpFailure(400, 'invalid_json'); }
      payload = validatePayload(payload, allowedModels);
      await takeSlot(controller.signal);
      acquired = true;
      const timeout = setTimeout(() => controller.abort(), upstreamLimit);
      try {
        const upstream = await fetchImpl('https://api.deepseek.com/chat/completions', {
          method: 'POST', redirect: 'error', signal: controller.signal,
          headers: { authorization: `Bearer ${apiKey.trim()}`, 'content-type': 'application/json' },
          body: JSON.stringify(payload),
        });
        if (!upstream.ok) {
          await upstream.body?.cancel().catch(() => {});
          const status = upstream.status === 429 ? 429 : upstream.status === 402 ? 402 : upstream.status >= 500 ? 503 : 502;
          const retryAfter = Number(upstream.headers.get('retry-after'));
          return send(res, status, upstream.status === 401 || upstream.status === 403 ? 'upstream_credentials_rejected' : 'upstream_request_failed',
            status === 429 ? { 'retry-after': String(Number.isFinite(retryAfter) ? Math.min(30, Math.max(1, retryAfter)) : 10) } : {});
        }
        const output = await readUpstream(upstream);
        if (!res.destroyed) {
          res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' });
          res.end(output);
        }
      } finally { clearTimeout(timeout); }
    } catch (error) {
      if (!res.destroyed) send(res, error instanceof HttpFailure ? error.status : controller.signal.aborted ? 504 : 502,
        error instanceof HttpFailure ? error.message : controller.signal.aborted ? 'upstream_timeout' : 'upstream_unavailable');
    } finally { if (acquired) releaseSlot(); }
  });
  server.headersTimeout = 10_000;
  server.requestTimeout = 30_000;
  server.keepAliveTimeout = 5_000;
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const server = createProxyServer({
      apiKey: process.env.DEEPSEEK_API_KEY,
      clientToken: process.env.CLIENT_TOKEN,
      models: (process.env.ALLOWED_MODELS || 'deepseek-flash').split(',').map(value => value.trim()).filter(Boolean),
    });
    const host = process.env.HOST || '127.0.0.1';
    const port = Number(process.env.PORT || 8787);
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('PORT must be between 1 and 65535');
    server.listen(port, host, () => console.log(`Proxy listening on ${host}:${port}`));
    server.on('error', () => { console.error('Proxy failed to listen; check HOST and PORT'); process.exitCode = 1; });
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
