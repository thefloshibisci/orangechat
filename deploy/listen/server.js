'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const netease = require('./vendor/one-earbud/server/music/netease');
const { createMusicAuthStore } = require('./vendor/one-earbud/server/music/store');

const PREFIX = '/api/listen';
const RADAR_ID = '3136952023';
const BODY_LIMIT = 32768;

function inputError(message, code = 'LISTEN_INPUT_ERROR', status = 400) {
  return Object.assign(new Error(message), { code, status });
}

function songId(value) {
  const id = String(value || '');
  if (!/^\d{1,32}$/.test(id)) throw inputError('歌曲或歌单编号无效');
  return id;
}

function cleanString(value, limit) {
  return typeof value === 'string' ? value.trim().slice(0, limit) : '';
}

function normalizeSong(value) {
  return {
    id: songId(value.songId),
    title: cleanString(value.title, 160),
    artist: cleanString(value.artist, 160),
    album: cleanString(value.album, 160),
    cover: cleanString(value.coverUrl, 1200),
    durationMs: Math.max(0, Number(value.durationMs) || 0)
  };
}

function publicAccount(value) {
  return {
    loggedIn: !!value.connected,
    nickname: cleanString(value.profile && value.profile.nickname, 80),
    avatarUrl: cleanString(value.profile && value.profile.avatarUrl, 1200),
    vip: !!value.vip
  };
}

function emptyNotes() {
  return { notes: '', feeling: '', favoriteLines: [], tags: [] };
}

function normalizeNotes(value) {
  const list = (field, maxItems, maxLength) => Array.isArray(value[field])
    ? value[field].slice(0, maxItems).map(item => cleanString(item, maxLength)).filter(Boolean)
    : [];
  return {
    notes: typeof value.notes === 'string' ? value.notes.slice(0, 4000) : '',
    feeling: typeof value.feeling === 'string' ? value.feeling.slice(0, 1000) : '',
    favoriteLines: list('favoriteLines', 20, 300),
    tags: list('tags', 20, 40)
  };
}

function validateNotesUpdate(value) {
  for (const [field, limit] of [['notes', 4000], ['feeling', 1000]]) {
    if (value[field] !== undefined && (typeof value[field] !== 'string' || value[field].length > limit)) {
      throw inputError(field === 'notes' ? '手记须为不超过4000字的文字' : '心情须为不超过1000字的文字');
    }
  }
  for (const [field, limit] of [['favoriteLines', 300], ['tags', 40]]) {
    if (value[field] !== undefined && (!Array.isArray(value[field]) || value[field].length > 20 ||
      value[field].some(item => typeof item !== 'string' || item.length > limit))) {
      throw inputError(field === 'favoriteLines' ? '喜欢的句子最多20句，每句不超过300字' : '标签最多20个，每个不超过40字');
    }
  }
}

function createListenService(options = {}) {
  const client = options.client || netease;
  const dataDir = path.resolve(options.dataDir || process.env.LISTEN_DATA_DIR || path.join(__dirname, 'data'));
  const store = options.store || createMusicAuthStore({ file: path.join(dataDir, 'netease-auth.json') });
  const now = options.now || Date.now;
  const pendingQr = new Map();
  let accountVersion = 0;

  async function withCookie(operation) {
    const version = accountVersion;
    const cookie = store.cookie();
    if (!cookie) throw inputError('请先扫码连接网易云', 'MUSIC_NOT_LOGGED_IN', 401);
    const result = await operation(cookie);
    if (version !== accountVersion) throw inputError('账号已变更，请重试', 'MUSIC_ACCOUNT_CHANGED', 409);
    if (result && result.cookie) store.refreshCookie(result.cookie);
    return result;
  }

  function notesFile(id) {
    return path.join(dataDir, 'notes', songId(id) + '.json');
  }

  function readNotes(id) {
    const file = notesFile(id);
    try {
      const saved = JSON.parse(fs.readFileSync(file, 'utf8'));
      if (!saved || typeof saved !== 'object' || Array.isArray(saved)) throw new Error('Invalid notes');
      validateNotesUpdate(saved);
      return normalizeNotes(saved);
    } catch (error) {
      if (error.code === 'ENOENT') return emptyNotes();
      throw inputError('歌曲手记读取失败，请保留数据并检查服务存储', 'LISTEN_STORAGE_ERROR', 500);
    }
  }

  async function pollQr(key) {
    const pending = pendingQr.get(key);
    if (!pending || pending.expiresAt <= now()) {
      pendingQr.delete(key);
      return { status: 'expired' };
    }
    if (pending.promise) return pending.promise;
    const version = accountVersion;
    pending.promise = (async () => {
      const result = await client.checkQr(key);
      if (version !== accountVersion || pendingQr.get(key) !== pending || pending.expiresAt <= now()) {
        if (pendingQr.get(key) === pending) pendingQr.delete(key);
        return { status: 'expired' };
      }
      if (result.status === 'expired') pendingQr.delete(key);
      if (result.status !== 'success') return { status: result.status };
      const account = await client.account(result.cookie);
      if (version !== accountVersion || pendingQr.get(key) !== pending || pending.expiresAt <= now()) {
        if (pendingQr.get(key) === pending) pendingQr.delete(key);
        return { status: 'expired' };
      }
      store.save(account.cookie || result.cookie, account.profile, account.vip, now());
      accountVersion += 1;
      pendingQr.clear();
      return Object.assign({ status: 'authorized' }, publicAccount(store.publicState()));
    })();
    try {
      return await pending.promise;
    } finally {
      pending.promise = null;
    }
  }

  return {
    async dispatch(method, endpoint, query, body) {
      if (method === 'GET' && endpoint === '/health') return { service: 'gongdu-listen', version: 1 };
      if (method === 'GET' && endpoint === '/login/start') {
        const version = accountVersion;
        const qr = await client.createQr();
        if (version !== accountVersion) throw inputError('账号已变更，请重新生成二维码', 'MUSIC_ACCOUNT_CHANGED', 409);
        for (const [key, value] of pendingQr) if (value.expiresAt < now()) pendingQr.delete(key);
        if (pendingQr.size >= 8) pendingQr.delete(pendingQr.keys().next().value);
        pendingQr.set(qr.key, { expiresAt: qr.expiresAt });
        return {
          key: qr.key,
          qrUrl: 'https://music.163.com/login?codekey=' + encodeURIComponent(qr.key),
          qrImage: qr.qrImage,
          expiresAt: qr.expiresAt
        };
      }
      if (method === 'GET' && endpoint === '/login/status') {
        const key = cleanString(query.get('key'), 120);
        if (!key) throw inputError('缺少二维码编号');
        return pollQr(key);
      }
      if (method === 'GET' && endpoint === '/login/account') {
        if (!store.cookie()) return publicAccount({ connected: false });
        const version = accountVersion;
        const account = await withCookie(cookie => client.account(cookie));
        if (version !== accountVersion) throw inputError('账号已变更，请重试', 'MUSIC_ACCOUNT_CHANGED', 409);
        store.save(store.cookie(), account.profile, account.vip, now());
        return publicAccount(store.publicState());
      }
      if (method === 'POST' && endpoint === '/login/logout') {
        accountVersion += 1;
        pendingQr.clear();
        store.clear();
        return publicAccount({ connected: false });
      }
      if (method === 'GET' && endpoint === '/search') {
        const keyword = cleanString(query.get('q'), 80);
        if (!keyword) throw inputError('请输入歌名或歌手');
        const songs = await withCookie(cookie => client.search(cookie, keyword, 30));
        return { songs: songs.map(normalizeSong) };
      }
      if (method === 'GET' && endpoint === '/song') {
        const song = await withCookie(cookie => client.songDetail(cookie, songId(query.get('id'))));
        return { song: normalizeSong(song) };
      }
      if (method === 'GET' && endpoint === '/url') {
        const result = await withCookie(cookie => client.playUrl(cookie, songId(query.get('id'))));
        return { url: result.url, trial: !!result.trial };
      }
      if (method === 'GET' && endpoint === '/lyrics') {
        const result = await withCookie(cookie => client.lyrics(cookie, songId(query.get('id'))));
        return {
          lines: result.lines.map(line => ({ timeMs: line.timeMs, text: line.text, translation: line.trans || '' })),
          pure: !!result.pure
        };
      }
      if (method === 'GET' && ['/daily', '/radar', '/playlist'].includes(endpoint)) {
        const songs = await withCookie(cookie => endpoint === '/daily'
          ? client.dailySongs(cookie)
          : client.playlistSongs(cookie, endpoint === '/radar' ? RADAR_ID : songId(query.get('id')), 500));
        return { songs: songs.map(normalizeSong) };
      }
      if (method === 'GET' && endpoint === '/playlists') {
        const playlists = await withCookie(async cookie => {
          const account = await client.account(cookie);
          return client.userPlaylists(account.cookie || cookie, account.profile.userId);
        });
        return {
          playlists: playlists.map(item => ({
            id: String(item.playlistId), title: item.name, cover: item.coverUrl, count: item.count
          }))
        };
      }
      if (method === 'GET' && endpoint === '/notes') return readNotes(query.get('id'));
      if (method === 'POST' && endpoint === '/notes') {
        const file = notesFile(query.get('id'));
        validateNotesUpdate(body);
        const previous = readNotes(query.get('id'));
        const notes = normalizeNotes(Object.assign(previous, body));
        fs.mkdirSync(path.dirname(file), { recursive: true });
        const temporary = file + '.tmp-' + crypto.randomUUID();
        try {
          fs.writeFileSync(temporary, JSON.stringify(notes), { mode: 0o600 });
          fs.renameSync(temporary, file);
        } finally {
          if (fs.existsSync(temporary)) fs.unlinkSync(temporary);
        }
        return notes;
      }
      throw inputError('接口不存在', 'LISTEN_NOT_FOUND', 404);
    }
  };
}

function sendJson(response, status, body) {
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff'
  });
  response.end(JSON.stringify(body));
}

async function readBody(request) {
  let size = 0;
  const chunks = [];
  for await (const chunk of request) {
    size += chunk.length;
    if (size > BODY_LIMIT) throw inputError('请求内容过大', 'LISTEN_BODY_TOO_LARGE', 413);
    chunks.push(chunk);
  }
  try {
    const body = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
    if (!body || typeof body !== 'object' || Array.isArray(body)) throw new Error();
    return body;
  } catch (_) {
    throw inputError('请求必须是 JSON 对象');
  }
}

function createListenServer(options = {}) {
  const token = String(options.token || process.env.LISTEN_TOKEN || '');
  if (token.length < 24) throw new Error('LISTEN_TOKEN must contain at least 24 characters');
  const tokenHash = crypto.createHash('sha256').update(token).digest();
  const service = options.service || createListenService(options);
  const server = http.createServer(async (request, response) => {
    const authorization = String(request.headers.authorization || '');
    const supplied = authorization.startsWith('Bearer ') ? authorization.slice(7) : '';
    const suppliedHash = crypto.createHash('sha256').update(supplied).digest();
    if (!crypto.timingSafeEqual(tokenHash, suppliedHash)) {
      return sendJson(response, 401, { ok: false, error: 'LISTEN_UNAUTHORIZED', message: '一起听服务令牌无效' });
    }
    try {
      const url = new URL(request.url, 'http://localhost');
      if (!url.pathname.startsWith(PREFIX + '/')) throw inputError('接口不存在', 'LISTEN_NOT_FOUND', 404);
      const body = request.method === 'POST' ? await readBody(request) : {};
      const result = await service.dispatch(request.method, url.pathname.slice(PREFIX.length), url.searchParams, body);
      sendJson(response, 200, Object.assign({ ok: true }, result));
    } catch (error) {
      const status = Number.isInteger(error.status) ? error.status : 500;
      const knownError = /^(MUSIC_|LISTEN_)/.test(error.code || '');
      sendJson(response, status, {
        ok: false,
        error: knownError ? error.code : 'LISTEN_FAILED',
        message: knownError ? error.message : '一起听服务暂时不可用'
      });
    }
  });
  server.requestTimeout = 20000;
  server.headersTimeout = 15000;
  return server;
}

if (require.main === module) {
  const server = createListenServer();
  const port = Number(process.env.LISTEN_PORT || 8787);
  const host = process.env.LISTEN_HOST || '127.0.0.1';
  server.listen(port, host, () => console.log('Gongdu listen adapter is ready on ' + host + ':' + port));
}

module.exports = { createListenServer, createListenService };
