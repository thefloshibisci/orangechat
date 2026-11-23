'use strict';

const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const https = require('node:https');
const { PassThrough } = require('node:stream');
const { test } = require('node:test');
const netease = require('../vendor/one-earbud/server/music/netease');

// Exercise the real provider parser without making network or account requests.
function reply(t, body, options = {}) {
  t.mock.method(https, 'request', (url, config, callback) => {
    const request = new EventEmitter();
    request.write = () => {};
    request.destroy = () => {};
    request.end = () => queueMicrotask(() => {
      const response = new PassThrough();
      response.statusCode = options.status || 200;
      response.headers = {};
      callback(response);
      if (options.aborted) response.emit('aborted');
      else response.end(JSON.stringify(body));
    });
    return request;
  });
}

test('provider business failures do not become empty daily recommendations', async t => {
  reply(t, { code: 301, message: 'private provider details' });
  await assert.rejects(netease.dailySongs('MUSIC_U=test'), error =>
    error.code === 'MUSIC_LOGIN_EXPIRED' && error.status === 401 && !error.message.includes('private'));
});

test('provider business failures do not become empty playlists', async t => {
  reply(t, { code: 500, message: 'secret-bearing provider message' });
  await assert.rejects(netease.userPlaylists('MUSIC_U=test', '17'), error =>
    error.code === 'MUSIC_UPSTREAM_ERROR' && error.status === 502 && !error.message.includes('secret'));
});

test('a successful empty recommendation remains distinguishable from failure', async t => {
  reply(t, { code: 200, data: { dailySongs: [] } });
  assert.deepEqual(await netease.dailySongs('MUSIC_U=test'), []);
});

test('QR waiting status passes through the provider parser', async t => {
  reply(t, { code: 801 });
  assert.deepEqual(await netease.checkQr('issued-key'), { status: 'waiting' });
});

test('an interrupted provider body fails promptly instead of leaving the caller pending', async t => {
  reply(t, null, { aborted: true });
  await assert.rejects(netease.dailySongs('MUSIC_U=test'), error => error.code === 'MUSIC_UPSTREAM_ERROR');
});

test('invalid provider JSON shapes are explicit errors', async t => {
  reply(t, []);
  await assert.rejects(netease.dailySongs('MUSIC_U=test'), error => error.code === 'MUSIC_BAD_RESPONSE');
});

test('an oversized provider response is rejected before JSON parsing', async t => {
  reply(t, { code: 200, data: 'x'.repeat(8 * 1024 * 1024) });
  await assert.rejects(netease.dailySongs('MUSIC_U=test'), error => error.code === 'MUSIC_BAD_RESPONSE');
});

test('a provider that never sends data is terminated by the total deadline', async t => {
  let deadline;
  let destroyed = false;
  t.mock.method(global, 'setTimeout', (callback, milliseconds) => {
    assert.equal(milliseconds, 8000);
    deadline = callback;
    return { unref() {} };
  });
  t.mock.method(global, 'clearTimeout', () => {});
  t.mock.method(https, 'request', () => {
    const request = new EventEmitter();
    request.write = () => {};
    request.end = () => {};
    request.destroy = () => { destroyed = true; };
    return request;
  });
  const result = netease.dailySongs('MUSIC_U=test');
  deadline();
  await assert.rejects(result, error => error.code === 'MUSIC_TIMEOUT' && error.status === 504);
  assert.equal(destroyed, true);
});
