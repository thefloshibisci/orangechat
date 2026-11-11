'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { test } = require('node:test');
const { once } = require('node:events');
const { createListenServer } = require('../server');
const { mergeLyrics } = require('../vendor/one-earbud/server/music/netease');

const TOKEN = 'test-token-with-at-least-24-characters';
const SONG = { songId: '42', title: '测试歌曲', artist: '歌手', album: '专辑', coverUrl: 'https://example.com/cover.jpg', durationMs: 240000 };

async function fixture(t, overrides = {}, options = {}) {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'gongdu-listen-test-'));
  const calls = [];
  const client = Object.assign({
    createQr: async () => ({ key: 'issued-key', qrImage: 'data:image/svg+xml;base64,PHN2Zy8+', expiresAt: Date.now() + 180000 }),
    checkQr: async key => { calls.push(['checkQr', key]); return { status: 'success', cookie: 'MUSIC_U=private-login-cookie' }; },
    account: async () => ({ profile: { userId: '17', nickname: '严槿', avatarUrl: 'https://example.com/avatar.jpg' }, vip: true, cookie: 'MUSIC_U=private-refreshed-cookie' }),
    search: async (cookie, q) => { calls.push(['search', cookie, q]); return [SONG]; },
    songDetail: async () => SONG,
    playUrl: async () => ({ url: 'https://music.example.com/test.aac', cookie: 'MUSIC_U=private-refreshed-cookie', trial: true }),
    lyrics: async () => ({ lines: [{ timeMs: 1000, text: '测试歌词', trans: '翻译' }], pure: false }),
    dailySongs: async () => [SONG],
    playlistSongs: async (cookie, id) => { calls.push(['playlistSongs', id]); return [SONG]; },
    userPlaylists: async (cookie, uid) => { calls.push(['userPlaylists', uid]); return [{ playlistId: '73', name: '我的歌单', count: 1, coverUrl: SONG.coverUrl }]; }
  }, overrides);
  const server = createListenServer({ token: TOKEN, client, dataDir, ...options });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const base = 'http://127.0.0.1:' + server.address().port + '/api/listen';
  t.after(async () => {
    await new Promise(resolve => { server.close(resolve); server.closeAllConnections(); });
    assert.equal(path.dirname(dataDir), path.resolve(os.tmpdir()));
    assert.ok(path.basename(dataDir).startsWith('gongdu-listen-test-'));
    fs.rmSync(dataDir, { recursive: true, force: true });
  });
  const request = async (endpoint, requestOptions = {}) => {
    const response = await fetch(base + endpoint, {
      ...requestOptions,
      headers: { Authorization: 'Bearer ' + TOKEN, 'Content-Type': 'application/json', ...requestOptions.headers }
    });
    return { status: response.status, body: await response.json() };
  };
  const login = async () => {
    await request('/login/start');
    return request('/login/status?key=issued-key');
  };
  return { request, login, calls, dataDir, server };
}

test('every endpoint requires the configured Bearer token, including health and login', async t => {
  const { request } = await fixture(t);
  for (const endpoint of ['/health', '/login/start', '/notes?id=42']) {
    const response = await request(endpoint, { headers: { Authorization: '' } });
    assert.equal(response.status, 401);
    assert.equal(response.body.error, 'LISTEN_UNAUTHORIZED');
  }
  assert.equal((await request('/health')).body.service, 'gongdu-listen');
});

test('fails startup when the service token is missing or too short', () => {
  assert.throws(() => createListenServer({ token: 'short' }), /at least 24/);
});

test('rejects unissued QR keys without sending them upstream', async t => {
  const { request, calls } = await fixture(t);
  assert.equal((await request('/login/status?key=unknown')).body.status, 'expired');
  assert.deepEqual(calls, []);
});

test('QR login exposes the public account and persists a private cookie with restrictive permissions', async t => {
  const { request, login, dataDir } = await fixture(t);
  const qr = (await request('/login/start')).body;
  assert.equal(qr.qrUrl, 'https://music.163.com/login?codekey=issued-key');
  assert.equal(qr.qrImage, 'data:image/svg+xml;base64,PHN2Zy8+');
  const response = await login();
  assert.equal(response.body.status, 'authorized');
  assert.equal(response.body.loggedIn, true);
  const account = await request('/login/account');
  assert.equal(account.body.nickname, '严槿');
  assert.doesNotMatch(JSON.stringify([qr, response.body, account.body]), /MUSIC_U|private-login|private-refreshed/);
  const authFile = path.join(dataDir, 'netease-auth.json');
  assert.match(fs.readFileSync(authFile, 'utf8'), /private-refreshed-cookie/);
  if (process.platform !== 'win32') assert.equal(fs.statSync(authFile).mode & 0o777, 0o600);
});

test('music lookup requires login and normalizes songs, playlists, lyrics and trial playback', async t => {
  const { request, login, calls } = await fixture(t);
  assert.equal((await request('/search?q=test')).status, 401);
  await login();
  const expected = { id: '42', title: '测试歌曲', artist: '歌手', album: '专辑', cover: SONG.coverUrl, durationMs: 240000 };
  for (const endpoint of ['/search?q=test', '/daily', '/radar', '/playlist?id=73']) {
    assert.deepEqual((await request(endpoint)).body.songs, [expected]);
  }
  assert.deepEqual((await request('/song?id=42')).body.song, expected);
  assert.deepEqual((await request('/lyrics?id=42')).body.lines, [{ timeMs: 1000, text: '测试歌词', translation: '翻译' }]);
  const url = (await request('/url?id=42')).body;
  assert.equal(url.url, 'https://music.example.com/test.aac');
  assert.equal(url.trial, true);
  assert.doesNotMatch(JSON.stringify(url), /cookie|private/);
  assert.deepEqual((await request('/playlists')).body.playlists, [{ id: '73', title: '我的歌单', cover: SONG.coverUrl, count: 1 }]);
  assert.ok(calls.some(call => call[0] === 'playlistSongs' && call[1] === '3136952023'));
  assert.ok(calls.some(call => call[0] === 'userPlaylists' && call[1] === '17'));
});

test('QR polling coalesces concurrent requests and logout prevents an in-flight login from restoring credentials', async t => {
  let resolveCheck;
  let count = 0;
  const pending = new Promise(resolve => { resolveCheck = resolve; });
  const { request, dataDir } = await fixture(t, { checkQr: async () => { count += 1; return pending; } });
  await request('/login/start');
  const first = request('/login/status?key=issued-key');
  const second = request('/login/status?key=issued-key');
  await new Promise(resolve => setTimeout(resolve, 20));
  await request('/login/logout', { method: 'POST', body: '{}' });
  resolveCheck({ status: 'success', cookie: 'MUSIC_U=private-login-cookie' });
  const responses = await Promise.all([first, second]);
  assert.equal(count, 1);
  assert.ok(responses.every(response => response.body.status === 'expired'));
  assert.equal(fs.existsSync(path.join(dataDir, 'netease-auth.json')), false);
});

test('logout invalidates a pending playback URL request and cannot revive its refreshed cookie', async t => {
  let resolvePlay;
  const pending = new Promise(resolve => { resolvePlay = resolve; });
  const { request, login, dataDir } = await fixture(t, { playUrl: () => pending });
  await login();
  const response = request('/url?id=42');
  await new Promise(resolve => setTimeout(resolve, 20));
  await request('/login/logout', { method: 'POST', body: '{}' });
  resolvePlay({ url: 'https://example.com/old-account.aac', cookie: 'MUSIC_U=stale' });
  assert.equal((await response).body.error, 'MUSIC_ACCOUNT_CHANGED');
  assert.equal(fs.existsSync(path.join(dataDir, 'netease-auth.json')), false);
});

test('a QR that expires during account lookup cannot save its login cookie', async t => {
  let currentTime = Date.now();
  let resolveAccount;
  let enteredAccount;
  const entered = new Promise(resolve => { enteredAccount = resolve; });
  const pending = new Promise(resolve => { resolveAccount = resolve; });
  const { request, dataDir } = await fixture(t, {
    createQr: async () => ({ key: 'issued-key', expiresAt: currentTime + 180000 }),
    account: () => { enteredAccount(); return pending; }
  }, { now: () => currentTime });
  await request('/login/start');
  const response = request('/login/status?key=issued-key');
  await entered;
  currentTime += 180001;
  resolveAccount({ profile: { userId: '17' }, cookie: 'MUSIC_U=expired-qr-cookie' });
  assert.equal((await response).body.status, 'expired');
  assert.equal(fs.existsSync(path.join(dataDir, 'netease-auth.json')), false);
});

test('an obsolete QR request cannot remove a newly issued pending key', async t => {
  let resolveFirstAccount;
  let enteredAccount;
  let accountCalls = 0;
  const entered = new Promise(resolve => { enteredAccount = resolve; });
  const pending = new Promise(resolve => { resolveFirstAccount = resolve; });
  const account = { profile: { userId: '17' }, cookie: 'MUSIC_U=current-cookie' };
  const { request } = await fixture(t, {
    account: () => ++accountCalls === 1 ? (enteredAccount(), pending) : Promise.resolve(account)
  });
  await request('/login/start');
  const first = request('/login/status?key=issued-key');
  await entered;
  await request('/login/start');
  resolveFirstAccount(account);
  assert.equal((await first).body.status, 'expired');
  assert.equal((await request('/login/status?key=issued-key')).body.status, 'authorized');
});

test('logout storage failures are reported rather than claiming the cookie was removed', async t => {
  const { request, login, dataDir } = await fixture(t);
  await login();
  const originalUnlink = fs.unlinkSync;
  const authFile = path.join(dataDir, 'netease-auth.json');
  const unlink = t.mock.method(fs, 'unlinkSync', file => {
    if (file === authFile) throw Object.assign(new Error('private filesystem details'), { code: 'EACCES' });
    return originalUnlink(file);
  });
  const response = await request('/login/logout', { method: 'POST', body: '{}' });
  assert.equal(response.status, 500);
  assert.equal(response.body.ok, false);
  assert.doesNotMatch(JSON.stringify(response.body), /private filesystem/);
  assert.equal(fs.existsSync(authFile), true);
  unlink.mock.restore();
});

test('notes use atomic per-song files and partial updates retain the other fields across server restarts', async t => {
  const { request, dataDir } = await fixture(t);
  assert.deepEqual((await request('/notes?id=42')).body, { ok: true, notes: '', feeling: '', favoriteLines: [], tags: [] });
  await request('/notes?id=42', { method: 'POST', body: JSON.stringify({ notes: '一起听的第一首', favoriteLines: ['喜欢的句子'], tags: ['晚安'] }) });
  const result = await request('/notes?id=42', { method: 'POST', body: JSON.stringify({ feeling: '安静' }) });
  assert.deepEqual(result.body, { ok: true, notes: '一起听的第一首', feeling: '安静', favoriteLines: ['喜欢的句子'], tags: ['晚安'] });
  const stored = JSON.parse(fs.readFileSync(path.join(dataDir, 'notes', '42.json'), 'utf8'));
  assert.equal(stored.notes, '一起听的第一首');
  assert.deepEqual(fs.readdirSync(path.join(dataDir, 'notes')), ['42.json']);
  const service = require('../server').createListenService({ dataDir });
  assert.deepEqual(await service.dispatch('GET', '/notes', new URLSearchParams('id=42'), {}), stored);
});

test('corrupt saved notes are reported without replacing the original file', async t => {
  const { request, dataDir } = await fixture(t);
  const directory = path.join(dataDir, 'notes');
  fs.mkdirSync(directory);
  fs.writeFileSync(path.join(directory, '42.json'), 'broken');
  const response = await request('/notes?id=42', { method: 'POST', body: '{"notes":"new"}' });
  assert.equal(response.status, 500);
  assert.equal(response.body.error, 'LISTEN_STORAGE_ERROR');
  assert.equal(fs.readFileSync(path.join(directory, '42.json'), 'utf8'), 'broken');
});

test('valid JSON with an invalid notes schema is retained for recovery', async t => {
  const { request, dataDir } = await fixture(t);
  const directory = path.join(dataDir, 'notes');
  fs.mkdirSync(directory);
  for (const value of ['[]', '"old text"', '{"notes":123}', '{"notes":"' + 'x'.repeat(4001) + '"}']) {
    fs.writeFileSync(path.join(directory, '42.json'), value);
    const response = await request('/notes?id=42', { method: 'POST', body: '{"notes":"new"}' });
    assert.equal(response.status, 500);
    assert.equal(response.body.error, 'LISTEN_STORAGE_ERROR');
    assert.equal(fs.readFileSync(path.join(directory, '42.json'), 'utf8'), value);
  }
});

test('note input limits reject invalid updates and preserve previously saved text', async t => {
  const { request } = await fixture(t);
  await request('/notes?id=42', { method: 'POST', body: JSON.stringify({ notes: '  原来的手记\n' }) });
  const invalid = [
    { notes: 'a'.repeat(4001) }, { feeling: 'a'.repeat(1001) },
    { favoriteLines: Array(21).fill('test') }, { favoriteLines: ['a'.repeat(301)] },
    { tags: ['a'.repeat(41)] }, { notes: 123 }, { tags: 'string' }
  ];
  for (const value of invalid) {
    assert.equal((await request('/notes?id=42', { method: 'POST', body: JSON.stringify(value) })).status, 400);
  }
  assert.equal((await request('/notes?id=42')).body.notes, '  原来的手记\n');
});

test('invalid IDs and malformed bodies return explicit errors', async t => {
  const { request } = await fixture(t);
  assert.equal((await request('/notes?id=../auth')).status, 400);
  assert.equal((await request('/notes?id=42', { method: 'POST', body: '[]' })).status, 400);
  assert.equal((await request('/notes?id=42', { method: 'POST', body: '{' })).status, 400);
  assert.equal((await request('/missing')).status, 404);
});

test('oversized JSON is rejected before notes are saved', async t => {
  const { request, dataDir } = await fixture(t);
  const response = await request('/notes?id=42', { method: 'POST', body: JSON.stringify({ notes: 'a'.repeat(40000) }) });
  assert.equal(response.status, 413);
  assert.equal(response.body.error, 'LISTEN_BODY_TOO_LARGE');
  assert.equal(fs.existsSync(path.join(dataDir, 'notes', '42.json')), false);
});

test('known upstream errors remain actionable and unexpected exceptions do not expose secret-bearing messages', async t => {
  const { request, login } = await fixture(t, {
    search: async () => { throw Object.assign(new Error('网易云登录过期了，要重新扫码'), { code: 'MUSIC_LOGIN_EXPIRED', status: 401 }); },
    dailySongs: async () => { throw new Error('private cookie MUSIC_U=do-not-return'); }
  });
  await login();
  assert.equal((await request('/search?q=test')).body.error, 'MUSIC_LOGIN_EXPIRED');
  const response = await request('/daily');
  assert.equal(response.status, 500);
  assert.doesNotMatch(JSON.stringify(response.body), /MUSIC_U|do-not-return/);
});

test('vendored LRC alignment preserves timed lyrics and translation', () => {
  assert.deepEqual(mergeLyrics('[00:01.00]测试歌词', '[00:01.10]翻译'), [
    { index: 0, timeMs: 1000, text: '测试歌词', trans: '翻译' }
  ]);
});
