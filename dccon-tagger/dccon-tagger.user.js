// ==UserScript==
// @name         DCCon Tagger
// @namespace    dccon-tagger
// @version      0.3.0
// @description  디시콘 AI 태그 검색 + 태그 직접 편집 (모바일 디시인사이드), PC에서는 로컬 AI(Ollama/KoboldCpp)로 자동 태깅
// @match        https://m.dcinside.com/*
// @match        https://gall.dcinside.com/*
// @match        https://www.dcinside.com/*
// @grant        GM_xmlhttpRequest
// @grant        GM_getValue
// @grant        GM_setValue
// @grant        GM_registerMenuCommand
// @grant        unsafeWindow
// @connect      dcinside.com
// @connect      localhost
// @connect      127.0.0.1
// @connect      *
// @run-at       document-idle
// @noframes
// ==/UserScript==

(function () {
  'use strict';

  const W = typeof unsafeWindow !== 'undefined' ? unsafeWindow : window;
  const M_ORIGIN = 'https://m.dcinside.com';

  // ---------- storage ----------
  // Keys: cfg, catIndex, packMeta, cat:<pack>, aiIndex, ai:<pack>, edits, recent, recentQ, ncEtags, lastPull, catAt
  const get = (k, def) => { const v = GM_getValue(k); return v === undefined ? def : v; };
  const set = (k, v) => GM_setValue(k, v);
  // api: 'ollama' or 'openai' (OpenAI-compatible: KoboldCpp, LM Studio, …). `ollama` holds the server address for either.
  const DEFAULT_CFG = { ncUrl: '', ncUser: '', ncPass: '', ncDir: 'DCConTagger/data', api: 'ollama', ollama: 'http://localhost:11434', model: 'qwen2.5vl:7b' };
  const DEFAULT_ADDR = { ollama: 'http://localhost:11434', openai: 'http://localhost:5001' };
  const cfg = () => Object.assign({}, DEFAULT_CFG, get('cfg', {}));

  const getAi = (p) => get('ai:' + p, null);
  function saveAi(p, data) {
    set('ai:' + p, data);
    const idx = get('aiIndex', []);
    if (!idx.includes(p)) { idx.push(p); set('aiIndex', idx); }
  }

  // ---------- utils ----------
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const uniq = (a) => [...new Set(a.map((x) => String(x).trim()).filter(Boolean))];
  const arr = (x) => (Array.isArray(x) ? x : x ? [x] : []);
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const norm = (s) => String(s || '').normalize('NFC').toLowerCase().replace(/\s+/g, '');
  const parse = (html) => new DOMParser().parseFromString(html, 'text/html');

  const CHO = 'ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ';
  function cho(s) {
    let out = '';
    for (const ch of norm(s)) {
      const c = ch.charCodeAt(0);
      out += c >= 0xac00 && c <= 0xd7a3 ? CHO[Math.floor((c - 0xac00) / 588)] : ch;
    }
    return out;
  }
  const isCho = (t) => /^[ㄱ-ㅎ]+$/.test(t);

  function el(tag, attrs, text) {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) e.setAttribute(k, v);
    if (text != null) e.textContent = text;
    return e;
  }

  function gm(opts) {
    return new Promise((ok, bad) => GM_xmlhttpRequest({
      timeout: 60000,
      ...opts,
      onload: ok,
      onerror: () => bad(Object.assign(new Error('네트워크 오류: ' + opts.url), { network: true })),
      ontimeout: () => bad(Object.assign(new Error('시간 초과: ' + opts.url), { network: true })),
    }));
  }
  const header = (r, name) => {
    const m = (r.responseHeaders || '').match(new RegExp('^' + name + ':\\s*(.*)$', 'im'));
    return m ? m[1].trim() : '';
  };

  // Calls a page-world function (insertDccon etc). Firefox needs cloneInto for objects crossing the sandbox.
  function pageCall(fn, arg) {
    const f = W[fn];
    if (typeof f !== 'function') throw new Error(fn + ' 함수를 찾을 수 없습니다');
    return f(typeof cloneInto === 'function' ? cloneInto(arg, W) : arg);
  }

  // ---------- DCInside data ----------
  async function postForm(path, body) {
    const headers = { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8', 'X-Requested-With': 'XMLHttpRequest' };
    if (location.origin === M_ORIGIN) {
      const r = await fetch(path, { method: 'POST', credentials: 'include', headers, body });
      if (!r.ok) throw new Error('디시 요청 실패 ' + r.status);
      return r.text();
    }
    const r = await gm({ method: 'POST', url: M_ORIGIN + path, data: body, headers: { ...headers, Referer: M_ORIGIN + '/' } });
    if (r.status !== 200) throw new Error('디시 요청 실패 ' + r.status);
    return r.responseText;
  }

  async function fetchPackList() {
    const doc = parse(await postForm('/dccon/getDccon', ''));
    const root = doc.querySelector('#dccon_package_list') || doc;
    const out = [], seen = new Set();
    for (const a of root.querySelectorAll('[onclick*="getDccon_tab"]')) {
      const m = a.getAttribute('onclick').match(/getDccon_tab\(\s*this\s*,\s*'(\d+)'(.*)\)/);
      // '0' is the recent-use tab; 'W' marks weekly-popular packs shown to guests, not owned ones
      if (!m || m[1] === '0' || /'W'/.test(m[2]) || seen.has(m[1])) continue;
      seen.add(m[1]);
      const img = a.querySelector('img');
      out.push({ id: m[1], title: img?.getAttribute('alt') || '', icon: img?.dataset.original || img?.getAttribute('src') || '' });
    }
    if (!out.length) throw new Error('디시콘 목록이 비어 있습니다 (로그인 확인)');
    return out;
  }

  async function fetchPack(id) {
    const doc = parse(await postForm('/dccon/getDccon_tab', 'idx=' + encodeURIComponent(id)));
    return [...doc.querySelectorAll('.sm-img')].map((a) => {
      const i = a.querySelector('img');
      return { d: a.dataset.dcconDetail, img: i?.dataset.src || i?.getAttribute('src') || '', alt: i?.getAttribute('alt') || '' };
    }).filter((x) => x.d);
  }

  function savePackMeta(packs) {
    const meta = get('packMeta', {});
    for (const p of packs) meta[p.id] = { title: p.title, icon: p.icon };
    set('packMeta', meta);
  }

  // Phone-side catalog: lets search cover packs before the AI has tagged them (by title / uploader name).
  let catBusy = false;
  async function refreshCatalog(full) {
    if (catBusy) return;
    catBusy = true;
    try {
      const packs = await fetchPackList();
      savePackMeta(packs);
      set('catIndex', packs.map((p) => p.id));
      let i = 0;
      for (const p of packs) {
        i++;
        if (!full && get('cat:' + p.id)) continue;
        setStatus(`디시콘 목록 읽는 중 ${i}/${packs.length}`);
        const s = await fetchPack(p.id);
        set('cat:' + p.id, { at: Date.now(), s: Object.fromEntries(s.map((x) => [x.d, { img: x.img, alt: x.alt }])) });
        await sleep(300);
      }
      set('catAt', Date.now());
    } catch (e) {
      setStatus('목록 읽기 실패: ' + e.message);
    } finally {
      catBusy = false;
      dirtyIndex();
      setStatus();
    }
  }

  // ---------- Nextcloud sync (WebDAV) ----------
  // Folder files: p_<pack>.json (written by the PC tagger), edits.json (manual edits from any device).
  const syncOn = (c = cfg()) => !!(c.ncUrl && c.ncUser && c.ncPass);
  function davDir() {
    const c = cfg();
    // Accept either the bare server address or one copied from the browser (…/index.php/apps/files etc).
    const base = c.ncUrl.trim().replace(/\/+$/, '').replace(/\/(index\.php|remote\.php|apps)(\/.*)?$/, '');
    const dir = (c.ncDir || DEFAULT_CFG.ncDir).split('/').filter(Boolean).map(encodeURIComponent).join('/');
    return `${base}/remote.php/dav/files/${encodeURIComponent(c.ncUser)}/${dir}/`;
  }
  async function dav(method, url, data, extra) {
    const c = cfg();
    if (!syncOn(c)) throw new Error('Nextcloud 설정이 비어 있습니다');
    const auth = btoa(String.fromCharCode(...new TextEncoder().encode(c.ncUser + ':' + c.ncPass)));
    let r;
    try {
      // anonymous: a Nextcloud login cookie in this browser would make WebDAV fail its CSRF check.
      r = await gm({ method, url, data, anonymous: true, headers: { Authorization: 'Basic ' + auth, ...(extra || {}) } });
    } catch {
      throw new Error('Nextcloud에 연결할 수 없습니다. Tailscale이 켜져 있는지 확인하세요.');
    }
    if (r.status === 401) throw new Error('Nextcloud 로그인 실패 (사용자 이름/앱 비밀번호 확인)');
    return r;
  }

  // Newest `at` wins per key.
  function mergeMap(a, b) {
    const out = { ...(a || {}) };
    for (const [k, v] of Object.entries(b || {})) if (!out[k] || (v?.at || 0) > (out[k]?.at || 0)) out[k] = v;
    return out;
  }
  const mergePack = (local, remote) => (local ? { ...local, ...remote, s: mergeMap(local.s, remote.s) } : remote);

  // Lists the folder with ETags and downloads only files that changed since the last pull.
  async function syncPull(force) {
    if (!syncOn()) return false;
    const dir = davDir();
    const r = await dav('PROPFIND', dir, '<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:getetag/></d:prop></d:propfind>', { Depth: '1', 'Content-Type': 'application/xml' });
    set('lastPull', Date.now());
    if (r.status === 404) return false; // folder is created on the first upload
    if (r.status !== 207) throw new Error('Nextcloud ' + r.status);
    const etags = force ? {} : get('ncEtags', {});
    const seen = {};
    let changed = false;
    const xml = new DOMParser().parseFromString(r.responseText, 'application/xml');
    for (const res of xml.getElementsByTagNameNS('DAV:', 'response')) {
      const href = res.getElementsByTagNameNS('DAV:', 'href')[0]?.textContent || '';
      const name = decodeURIComponent(href.replace(/\/+$/, '').split('/').pop());
      const etag = res.getElementsByTagNameNS('DAV:', 'getetag')[0]?.textContent || '';
      const m = name.match(/^p_(\d+)\.json$/);
      if (!m && name !== 'edits.json') continue;
      seen[name] = etag;
      if (etag && etags[name] === etag) continue;
      const f = await dav('GET', dir + encodeURIComponent(name));
      if (f.status !== 200) continue;
      let data;
      try { data = JSON.parse(f.responseText || '{}'); } catch { continue; }
      if (m) saveAi(m[1], mergePack(getAi(m[1]), data));
      else set('edits', mergeMap(get('edits', {}), data));
      changed = true;
    }
    set('ncEtags', seen);
    if (changed) dirtyIndex();
    return changed;
  }

  // Creates the sync folder (and its parents) once per page load.
  let dirReady = false;
  async function ensureDir() {
    if (dirReady) return;
    const dir = davDir();
    const root = dir.slice(0, dir.indexOf('/remote.php/dav/files/') + '/remote.php/dav/files/'.length) + encodeURIComponent(cfg().ncUser) + '/';
    let url = root;
    for (const part of dir.slice(root.length).split('/').filter(Boolean)) {
      url += part + '/';
      const r = await dav('MKCOL', url);
      if (r.status !== 201 && r.status !== 405) throw new Error('Nextcloud 폴더 만들기 실패 ' + r.status);
    }
    dirReady = true;
  }

  // files: { name: jsonString }
  async function pushFiles(files) {
    if (!syncOn()) return;
    await ensureDir();
    const etags = get('ncEtags', {});
    for (const [name, content] of Object.entries(files)) {
      const r = await dav('PUT', davDir() + encodeURIComponent(name), content, { 'Content-Type': 'application/json' });
      if (r.status >= 300) throw new Error('Nextcloud 업로드 실패 ' + r.status);
      // Remember our own upload so the next pull doesn't download it again.
      const etag = header(r, 'etag');
      if (etag) etags[name] = etag; else delete etags[name];
    }
    set('ncEtags', etags);
  }
  const pushPack = (p) => pushFiles({ [`p_${p}.json`]: JSON.stringify(getAi(p)) });
  const pushEdits = () => pushFiles({ 'edits.json': JSON.stringify(get('edits', {})) });

  let editsTimer = null;
  function schedulePushEdits() {
    clearTimeout(editsTimer);
    editsTimer = setTimeout(async () => {
      try {
        await syncPull(false); // pick up other devices' edits first
        await pushEdits();
        setStatus();
      } catch (e) {
        setStatus('동기화 실패: ' + e.message);
      }
    }, 2500);
  }

  // ---------- search index ----------
  let INDEX = null;
  function dirtyIndex() { INDEX = null; }

  function buildIndex() {
    const catIds = get('catIndex', []);
    const ids = catIds.length ? catIds : get('aiIndex', []);
    const meta = get('packMeta', {});
    const edits = get('edits', {});
    const out = [];
    for (const p of ids) {
      const cat = get('cat:' + p, null), ai = getAi(p);
      const title = meta[p]?.title || ai?.title || '';
      const icon = meta[p]?.icon || ai?.icon || '';
      const details = [...new Set([...Object.keys(cat?.s || {}), ...Object.keys(ai?.s || {})])].sort((a, b) => a - b);
      for (const d of details) {
        const c = cat?.s?.[d] || {}, a = ai?.s?.[d] || {};
        const key = p + ':' + d;
        const ed = edits[key] && !edits[key].del ? edits[key] : null;
        const aiOk = !!a.at && !a.err;
        const ocr = ed && ed.t != null ? ed.t : aiOk ? a.t || '' : '';
        const tags = ed ? arr(ed.k) : aiOk ? uniq([...arr(a.e), ...arr(a.k)]) : [];
        const alt = c.alt || a.alt || '';
        out.push({
          key, p, d, img: c.img || a.img || '', alt, ocr, tags, desc: aiOk ? a.d || '' : '', title, icon,
          tagged: aiOk || !!ed, edited: !!ed, model: aiOk ? a.m || '' : '',
          nOcr: norm(ocr), nTags: tags.map(norm), nAlt: norm(alt), nDesc: norm(a.d), nTitle: norm(title),
          cho: cho([ocr, ...tags, alt].join(' ')),
        });
      }
    }
    INDEX = out;
    return out;
  }

  const SYN = [
    ['기쁨', '웃음', '행복', '좋아', 'happy', 'smile'],
    ['슬픔', '울음', '눈물', 'ㅠㅠ', 'sad', 'cry'],
    ['화남', '분노', '짜증', '빡침', 'angry'],
    ['놀람', '깜짝', '헉', 'surprised'],
    ['감사', '고마워', '고맙', '땡큐', 'thanks', 'thank'],
    ['사과', '미안', '죄송', 'sorry'],
    ['인사', '안녕', '하이', 'hi', 'hello'],
    ['동의', '알겠', 'ㅇㅋ', '오케이', 'ok', 'okay'],
    ['거절', '싫어', 'ㄴㄴ', 'no'],
    ['당황', '어리둥절', '혼란', 'confused'],
    ['졸림', '잠', '피곤', 'sleep', 'tired'],
    ['사랑', '하트', 'love', 'heart'],
  ].map((g) => g.map(norm));
  const variants = (t) => [t, ...SYN.filter((g) => g.includes(t)).flat().filter((v) => v !== t)];

  function termScore(it, t) {
    if (isCho(t)) return it.cho.includes(t) ? 4 : 0;
    let best = 0;
    for (const v of variants(t)) {
      let s = 0;
      if (it.nTags.includes(v)) s = 10;
      else if (it.nTags.some((x) => x.includes(v))) s = 6;
      if (it.nOcr && it.nOcr.includes(v)) s = Math.max(s, it.nOcr === v ? 10 : 7);
      if (it.nAlt.includes(v)) s = Math.max(s, 5);
      if (!s && it.nDesc.includes(v)) s = 2;
      if (!s && it.nTitle.includes(v)) s = 1;
      best = Math.max(best, v === t ? s : s * 0.7);
    }
    return best;
  }

  function search(q) {
    const terms = q.trim().split(/\s+/).map(norm).filter(Boolean);
    if (!terms.length) return [];
    const recent = get('recent', []);
    const res = [];
    for (const it of INDEX || buildIndex()) {
      let total = 0;
      for (const t of terms) {
        const s = termScore(it, t);
        if (!s) { total = 0; break; }
        total += s;
      }
      if (total) res.push([total + (recent.includes(it.key) ? 0.5 : 0), it]);
    }
    return res.sort((a, b) => b[0] - a[0]).map((x) => x[1]);
  }

  function recordRecent(key) {
    set('recent', [key, ...get('recent', []).filter((k) => k !== key)].slice(0, 32));
  }
  function recordQuery(q) {
    q = q.trim();
    if (q) set('recentQ', [q, ...get('recentQ', []).filter((x) => x !== q)].slice(0, 8));
  }
  function findItem(key) {
    return (INDEX || buildIndex()).find((it) => it.key === key);
  }

  // ---------- styles ----------
  const CSS = `
  .dct-pill{flex:1 1 auto;min-width:0;height:28px;margin:0 8px;padding:0 10px;border:1px solid #c9cdd9;border-radius:14px;background:#f4f5f9;color:#666;font-size:13px;text-align:left;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;-webkit-tap-highlight-color:transparent}
  .dct-searching>*:not(.dccon-top-menu-wrap):not(.dct-panel){display:none!important}
  .dct-panel{display:none;padding:6px 8px 4px;background:#fff}
  .dct-searching>.dct-panel{display:block}
  .dct-bar{display:flex;gap:6px;align-items:center}
  .dct-bar input{flex:1;min-width:0;height:36px;padding:0 10px;border:2px solid #3b4890;border-radius:8px;font-size:16px;background:#fff;color:#222}
  .dct-bar button,.dct-foot button{height:36px;min-width:36px;border:0;border-radius:8px;background:#eef0f6;color:#333;font-size:16px}
  .dct-chips{display:flex;gap:6px;overflow-x:auto;padding:6px 0 2px;scrollbar-width:none}
  .dct-chips:empty{display:none}
  .dct-chip{flex:0 0 auto;padding:4px 10px;border-radius:12px;background:#eef0f6;color:#3b4890;font-size:13px;border:0}
  .dct-label{font-size:12px;color:#888;margin:6px 2px 2px}
  .dct-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:4px;height:300px;overflow-y:auto;overscroll-behavior:contain;align-content:start;padding-top:4px}
  .dct-cell{position:relative;aspect-ratio:1;border:0;padding:2px;background:#fff;border-radius:6px;-webkit-touch-callout:none;user-select:none;-webkit-user-select:none}
  .dct-cell:active{background:#e3e6f2}
  .dct-cell>img.dct-main{width:100%;height:100%;object-fit:contain;pointer-events:none}
  .dct-cell>img.dct-badge{position:absolute;left:2px;bottom:2px;width:18px;height:18px;border-radius:4px;border:1px solid #fff;object-fit:cover;pointer-events:none}
  .dct-cell.dct-untagged::after{content:'';position:absolute;right:4px;top:4px;width:6px;height:6px;border-radius:3px;background:#f0a020}
  .dct-cell.dct-edited::after{content:'';position:absolute;right:4px;top:4px;width:6px;height:6px;border-radius:3px;background:#3b4890}
  .dct-empty{grid-column:1/-1;padding:24px 8px;text-align:center;color:#888;font-size:13px;line-height:1.6}
  .dct-more{grid-column:1/-1;height:36px;border:0;border-radius:8px;background:#eef0f6;color:#3b4890}
  .dct-foot{display:flex;align-items:center;gap:6px;padding-top:4px;font-size:11px;color:#888}
  .dct-foot span{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .dct-foot button{height:28px;min-width:28px;font-size:14px}
  .sm-img{-webkit-touch-callout:none}
  .dct-bg{position:fixed;inset:0;z-index:2147483000;background:rgba(0,0,0,.45);display:flex;align-items:flex-end;justify-content:center}
  .dct-sheet{width:100%;max-width:520px;max-height:88vh;overflow-y:auto;background:#fff;color:#222;border-radius:14px 14px 0 0;padding:14px 16px calc(14px + env(safe-area-inset-bottom));box-sizing:border-box;font-size:14px}
  .dct-sheet h3{margin:0 0 10px;font-size:16px}
  .dct-sheet h4{margin:16px 0 6px;font-size:14px;color:#3b4890}
  .dct-sheet label{display:block;margin:8px 0 4px;font-size:12px;color:#666}
  .dct-sheet input[type=text],.dct-sheet input[type=password],.dct-sheet select,.dct-sheet textarea{width:100%;box-sizing:border-box;padding:8px 10px;border:1px solid #c9cdd9;border-radius:8px;font-size:16px;color:#222;background:#fff}
  .dct-sheet .dct-row{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}
  .dct-sheet .dct-row button{flex:1 1 auto;height:40px;padding:0 12px;border:0;border-radius:8px;background:#eef0f6;color:#333;font-size:14px}
  .dct-sheet .dct-row button.dct-primary{background:#3b4890;color:#fff}
  .dct-sheet .dct-head{display:flex;gap:12px;align-items:center}
  .dct-sheet .dct-head img{width:96px;height:96px;object-fit:contain;border-radius:8px;background:#f4f5f9}
  .dct-sheet .dct-head small{display:block;color:#888;margin-top:2px}
  .dct-tagbox{display:flex;flex-wrap:wrap;gap:6px;padding:6px;border:1px solid #c9cdd9;border-radius:8px;min-height:40px;box-sizing:border-box}
  .dct-tag{display:inline-flex;align-items:center;gap:2px;padding:4px 4px 4px 10px;border-radius:14px;background:#eef0f6;color:#3b4890;font-size:14px}
  .dct-tag button{border:0;background:none;color:#888;font-size:16px;line-height:1;padding:0 4px}
  .dct-tagbox input{flex:1;min-width:90px;border:0!important;outline:0;font-size:16px;padding:4px!important}
  .dct-desc{margin-top:8px;font-size:12px;color:#888;line-height:1.5}
  .dct-log{margin-top:8px;max-height:180px;overflow-y:auto;background:#f4f5f9;border-radius:8px;padding:8px;font:12px/1.5 ui-monospace,monospace;white-space:pre-wrap}
  .dct-stat{color:#555;line-height:1.6}`;

  // ---------- search panel ----------
  const panels = new Set();

  function statusText() {
    const idx = INDEX || buildIndex();
    const tagged = idx.filter((x) => x.tagged).length;
    const c = cfg();
    const sync = !syncOn(c) ? '동기화 꺼짐' : get('lastPull') ? '동기화 ' + new Date(get('lastPull')).toLocaleTimeString().replace(/:\d\d(?=\D*$)/, '') : '미동기화';
    return `AI 태그 ${tagged.toLocaleString()}/${idx.length.toLocaleString()} · ${sync}`;
  }
  let statusOverride = '';
  function setStatus(msg) {
    statusOverride = msg || '';
    for (const p of panels) {
      if (!p.isConnected) { panels.delete(p); continue; }
      p.querySelector('.dct-foot span').textContent = statusOverride || statusText();
    }
  }

  function injectMenu(menu) {
    menu.dataset.dct = '1';
    const ly = menu.closest('.dccon-lyitem');
    if (!ly) return;
    const pill = el('button', { type: 'button', class: 'dct-pill' }, '🔍 디시콘 검색');
    menu.insertBefore(pill, menu.querySelector('.rt'));
    pill.addEventListener('click', (e) => { e.preventDefault(); openSearch(ly); });

    if (Date.now() - get('catAt', 0) > 6 * 3600e3) refreshCatalog(false);
    if (Date.now() - get('lastPull', 0) > 10 * 60e3) syncPull(false).then((ch) => { if (ch) rerunAll(); setStatus(); }).catch(() => {});
  }

  function openSearch(ly) {
    let panel = ly.querySelector(':scope > .dct-panel');
    if (!panel) {
      panel = el('div', { class: 'dct-panel' });
      panel.innerHTML = `
        <div class="dct-bar"><input type="search" enterkeyhint="search" autocomplete="off" placeholder="글자·감정·태그 검색 (초성 OK)"><button type="button" class="dct-x" aria-label="검색 닫기">✕</button></div>
        <div class="dct-chips"></div>
        <div class="dct-label"></div>
        <div class="dct-grid"></div>
        <div class="dct-foot"><span></span><button type="button" class="dct-gear" aria-label="설정">⚙</button></div>`;
      ly.querySelector('.dccon-top-menu-wrap').after(panel);
      panels.add(panel);
      const input = panel.querySelector('input');
      let t = null;
      input.addEventListener('input', () => { clearTimeout(t); t = setTimeout(() => run(panel), 120); });
      input.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.isComposing) { e.preventDefault(); recordQuery(input.value); input.blur(); } });
      input.addEventListener('change', () => recordQuery(input.value));
      input.addEventListener('focus', () => setTimeout(() => ly.scrollIntoView({ block: 'start', behavior: 'smooth' }), 250));
      panel.querySelector('.dct-x').addEventListener('click', () => closeSearch(ly));
      panel.querySelector('.dct-gear').addEventListener('click', openSettings);
      panel.querySelector('.dct-chips').addEventListener('click', (e) => {
        const c = e.target.closest('.dct-chip');
        if (c) { input.value = c.textContent; run(panel); }
      });
      panel.querySelector('.dct-grid').addEventListener('click', (e) => {
        if (e.target.closest('.dct-more')) { panel._limit += 80; render(panel); return; }
        const cell = e.target.closest('.dct-cell');
        if (!cell) return;
        const it = panel._map.get(cell.dataset.key);
        if (it) { recordQuery(input.value); send(it); }
      });
    }
    ly.classList.add('dct-searching');
    run(panel);
    setStatus(statusOverride);
    panel.querySelector('input').focus({ preventScroll: true });
  }

  function closeSearch(ly) {
    ly.classList.remove('dct-searching');
  }

  function run(panel) {
    const q = panel.querySelector('input').value;
    const chips = panel.querySelector('.dct-chips');
    chips.replaceChildren(...(q.trim() ? [] : get('recentQ', []).map((x) => el('button', { type: 'button', class: 'dct-chip' }, x))));
    if (q.trim()) {
      panel._items = search(q);
      panel._label = `검색 결과 ${panel._items.length}개 · 길게 누르면 태그 편집`;
    } else {
      panel._items = get('recent', []).map(findItem).filter(Boolean);
      panel._label = panel._items.length ? '최근 사용' : '';
    }
    panel._limit = 80;
    render(panel);
  }
  function rerunAll() { for (const p of panels) if (p.isConnected && p.closest('.dct-searching')) run(p); }

  function render(panel) {
    const grid = panel.querySelector('.dct-grid');
    const q = panel.querySelector('input').value.trim();
    panel.querySelector('.dct-label').textContent = panel._label;
    panel._map = new Map();
    const frag = document.createDocumentFragment();
    for (const it of panel._items.slice(0, panel._limit)) {
      panel._map.set(it.key, it);
      const cell = el('button', { type: 'button', class: 'dct-cell' + (it.edited ? ' dct-edited' : it.tagged ? '' : ' dct-untagged'), title: it.tags.slice(0, 6).join(', ') || it.alt });
      cell.dataset.key = it.key;
      cell.append(el('img', { class: 'dct-main', loading: 'lazy', src: it.img, alt: it.alt }));
      if (it.icon) cell.append(el('img', { class: 'dct-badge', loading: 'lazy', src: it.icon, alt: '' }));
      frag.append(cell);
    }
    if (panel._items.length > panel._limit) frag.append(el('button', { type: 'button', class: 'dct-more' }, `더 보기 (${panel._items.length - panel._limit})`));
    if (!panel._items.length) {
      const empty = el('div', { class: 'dct-empty' });
      const total = (INDEX || buildIndex()).length;
      empty.textContent = q
        ? '검색 결과가 없습니다.'
        : total
          ? '이미지 속 글자, 감정(기쁨·슬픔…), 상황(감사·사과…)으로 검색하세요.\n초성 검색도 됩니다 (예: ㅇㄱㅇㅇ).\n디시콘을 길게 누르면 태그를 고칠 수 있어요.'
          : '디시콘 목록을 읽는 중입니다…';
      empty.style.whiteSpace = 'pre-line';
      frag.append(empty);
    }
    grid.replaceChildren(frag);
    grid.scrollTop = 0;
  }

  function send(it) {
    recordRecent(it.key);
    try {
      pageCall('insertDccon', { dcconPackage: it.p, dcconDetail: it.d });
    } catch (e) {
      alert('디시콘 전송 실패: ' + e.message);
    }
  }

  // ---------- long-press (search results + the site's own grid) ----------
  const LP_SEL = '.dct-cell, .sm-img';
  let lp = null, suppressUntil = 0;
  document.addEventListener('pointerdown', (e) => {
    const t = e.target.closest(LP_SEL);
    if (!t) return;
    const x = e.clientX, y = e.clientY;
    lp = {
      x, y,
      timer: setTimeout(() => {
        lp = null;
        suppressUntil = Date.now() + 800;
        const it = itemFromEl(t);
        if (it) { navigator.vibrate?.(15); openEditor(it); }
      }, 450),
    };
  }, true);
  document.addEventListener('pointermove', (e) => {
    if (lp && Math.hypot(e.clientX - lp.x, e.clientY - lp.y) > 10) { clearTimeout(lp.timer); lp = null; }
  }, true);
  for (const ev of ['pointerup', 'pointercancel']) document.addEventListener(ev, () => { if (lp) { clearTimeout(lp.timer); lp = null; } }, true);
  // Swallow the click that follows a long-press, wherever it lands (it would otherwise send the sticker or close the panel).
  window.addEventListener('click', (e) => {
    if (Date.now() < suppressUntil) {
      suppressUntil = 0;
      e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
      return;
    }
    const sm = e.target.closest('.sm-img');
    if (sm) recordRecent(sm.dataset.dcconPackage + ':' + sm.dataset.dcconDetail);
  }, true);
  document.addEventListener('contextmenu', (e) => { if (e.target.closest(LP_SEL)) e.preventDefault(); }, true);

  function itemFromEl(t) {
    if (t.classList.contains('dct-cell')) {
      const panel = t.closest('.dct-panel');
      return panel?._map.get(t.dataset.key);
    }
    const key = t.dataset.dcconPackage + ':' + t.dataset.dcconDetail;
    const found = findItem(key);
    if (found) return found;
    const img = t.querySelector('img');
    return {
      key, p: t.dataset.dcconPackage, d: t.dataset.dcconDetail, img: img?.dataset.src || img?.src || '', alt: img?.alt || '',
      ocr: '', tags: [], desc: '', title: get('packMeta', {})[t.dataset.dcconPackage]?.title || '', tagged: false, edited: false, model: '',
    };
  }

  // ---------- sheets ----------
  function sheet(html) {
    const bg = el('div', { class: 'dct-bg' });
    bg.innerHTML = `<div class="dct-sheet">${html}</div>`;
    const t0 = Date.now();
    // Stop clicks reaching <body>, where the site closes the DCCon box.
    bg.addEventListener('click', (e) => {
      e.stopPropagation();
      if (e.target === bg && Date.now() - t0 > 400) bg.remove();
    });
    document.body.append(bg);
    return bg;
  }

  function openEditor(it) {
    const bg = sheet(`
      <div class="dct-head"><img alt=""><div><b class="dct-alt"></b><small class="dct-pack"></small><small class="dct-src"></small></div></div>
      <label>이미지 속 글자</label><input type="text" class="dct-ocr" placeholder="(없음)">
      <label>태그 · 엔터나 쉼표로 추가</label><div class="dct-tagbox"><input type="text" class="dct-add" enterkeyhint="done" placeholder="태그 추가"></div>
      <div class="dct-desc"></div>
      <div class="dct-row"><button type="button" data-a="reset">AI 태그로 되돌리기</button></div>
      <div class="dct-row"><button type="button" data-a="cancel">취소</button><button type="button" data-a="send">이 콘 보내기</button><button type="button" class="dct-primary" data-a="save">저장</button></div>`);
    const $ = (s) => bg.querySelector(s);
    $('img').src = it.img;
    $('.dct-alt').textContent = it.alt || '(이름 없음)';
    $('.dct-pack').textContent = it.title;
    $('.dct-src').textContent = it.edited ? '직접 수정한 태그' : it.tagged ? 'AI 태그 · ' + it.model : '아직 AI 태그 없음';
    $('.dct-ocr').value = it.ocr;
    $('.dct-desc').textContent = it.desc ? 'AI 설명: ' + it.desc : '';
    if (!it.edited) $('[data-a=reset]').parentElement.remove();

    let tags = [...it.tags];
    const box = $('.dct-tagbox'), add = $('.dct-add');
    const draw = () => {
      box.querySelectorAll('.dct-tag').forEach((x) => x.remove());
      tags.forEach((t, i) => {
        const chip = el('span', { class: 'dct-tag' }, t);
        const x = el('button', { type: 'button', 'aria-label': t + ' 삭제' }, '×');
        x.addEventListener('click', () => { tags.splice(i, 1); draw(); });
        chip.append(x);
        box.insertBefore(chip, add);
      });
    };
    const commit = () => {
      const parts = add.value.split(/[,，]/).map((s) => s.trim()).filter(Boolean);
      if (parts.length) { tags = uniq([...tags, ...parts]); add.value = ''; draw(); }
    };
    add.addEventListener('keydown', (e) => {
      if (e.isComposing) return;
      if (e.key === 'Enter') { e.preventDefault(); commit(); }
      else if (e.key === 'Backspace' && !add.value && tags.length) { tags.pop(); draw(); }
    });
    add.addEventListener('input', () => { if (/[,，]/.test(add.value)) commit(); });
    // Android keyboards often send keyCode 229 instead of Enter; 'change' fires on their enter/done key.
    add.addEventListener('change', commit);
    draw();

    const saveEdit = (val) => {
      const edits = get('edits', {});
      edits[it.key] = { ...val, at: Date.now() };
      set('edits', edits);
      dirtyIndex();
      schedulePushEdits();
      rerunAll();
      setStatus();
    };
    bg.addEventListener('click', (e) => {
      const a = e.target.closest('[data-a]')?.dataset.a;
      if (!a) return;
      if (a === 'save') { commit(); saveEdit({ k: tags, t: $('.dct-ocr').value.trim() }); }
      if (a === 'reset') saveEdit({ del: true });
      if (a === 'send') send(it);
      bg.remove();
    });
  }

  // ---------- settings + PC tagger ----------
  const logLines = [];
  function log(msg) {
    logLines.push(new Date().toLocaleTimeString() + ' ' + msg);
    if (logLines.length > 300) logLines.shift();
    const box = document.querySelector('.dct-log');
    if (box) { box.textContent = logLines.join('\n'); box.scrollTop = box.scrollHeight; }
  }

  function openSettings() {
    const c = cfg();
    const bg = sheet(`
      <h3>DCCon Tagger 설정</h3>
      <div class="dct-stat"></div>
      <h4>동기화 (Nextcloud)</h4>
      <label>Nextcloud 주소</label><input type="url" name="ncUrl" placeholder="https://nextcloud.example.com" autocomplete="off">
      <label>사용자 이름</label><input type="text" name="ncUser" autocomplete="off" autocapitalize="off">
      <label>앱 비밀번호 (Nextcloud 설정 → 보안에서 만들기)</label><input type="password" name="ncPass" autocomplete="off">
      <label>폴더</label><input type="text" name="ncDir" autocomplete="off" autocapitalize="off">
      <div class="dct-row"><button type="button" data-a="sync">연결 확인 + 지금 동기화</button></div>
      <h4>검색 목록</h4>
      <div class="dct-row"><button type="button" data-a="catalog">디시콘 목록 전체 다시 읽기</button></div>
      <h4>AI 태깅 (PC)</h4>
      <label>AI 서버 종류</label><select name="api"><option value="ollama">Ollama</option><option value="openai">OpenAI 호환 (KoboldCpp, LM Studio 등)</option></select>
      <label>AI 서버 주소</label><input type="text" name="ollama">
      <label>모델 (KoboldCpp는 비워 둬도 됨)</label><input type="text" name="model">
      <div class="dct-row"><button type="button" class="dct-primary" data-a="start">태깅 시작</button><button type="button" data-a="stop">중지</button><button type="button" data-a="retag">선택 팩 다시 태깅…</button></div>
      <div class="dct-log"></div>
      <div class="dct-row"><button type="button" data-a="close">닫기</button><button type="button" class="dct-primary" data-a="save">저장</button></div>`);
    const $ = (s) => bg.querySelector(s);
    const FIELDS = ['ncUrl', 'ncUser', 'ncPass', 'ncDir', 'api', 'ollama', 'model'];
    for (const k of FIELDS) $(`[name=${k}]`).value = c[k];
    $('[name=api]').addEventListener('change', (e) => {
      const addr = $('[name=ollama]');
      if (Object.values(DEFAULT_ADDR).includes(addr.value.trim())) addr.value = DEFAULT_ADDR[e.target.value];
      if (e.target.value === 'openai' && $('[name=model]').value === DEFAULT_CFG.model) $('[name=model]').value = '';
    });
    const stat = () => {
      const idx = INDEX || buildIndex();
      $('.dct-stat').textContent = `디시콘 ${idx.length.toLocaleString()}개 · AI 태그 ${idx.filter((x) => x.tagged && !x.edited).length.toLocaleString()} · 직접 수정 ${idx.filter((x) => x.edited).length} · 팩 ${new Set(idx.map((x) => x.p)).size}개`;
    };
    stat();
    $('.dct-log').textContent = logLines.join('\n');
    const save = () => set('cfg', Object.fromEntries(FIELDS.map((k) => [k, $(`[name=${k}]`).value.trim()])));

    bg.addEventListener('click', async (e) => {
      const btn = e.target.closest('[data-a]');
      if (!btn) return;
      const a = btn.dataset.a;
      if (a === 'close') return bg.remove();
      save();
      try {
        if (a === 'save') { bg.remove(); syncPull(true).then(() => { rerunAll(); setStatus(); }).catch((er) => setStatus('동기화 실패: ' + er.message)); }
        if (a === 'sync') {
          if (!syncOn()) return log('Nextcloud 주소, 사용자 이름, 앱 비밀번호를 모두 입력하세요.');
          log('동기화 중… ' + davDir());
          await syncPull(true); await pushEdits();
          log('동기화 완료'); stat(); rerunAll(); setStatus();
        }
        if (a === 'catalog') { log('디시콘 목록 읽는 중…'); await refreshCatalog(true); log('목록 갱신 완료'); stat(); }
        if (a === 'start') runTagger();
        if (a === 'stop') { if (tagRun) { tagRun.stop = true; log('현재 디시콘까지 처리하고 멈춥니다…'); } }
        if (a === 'retag') {
          const name = prompt('다시 태깅할 팩 이름(일부)을 입력하세요');
          if (!name) return;
          const meta = get('packMeta', {});
          const ids = Object.keys(meta).filter((id) => norm(meta[id].title).includes(norm(name)));
          if (!ids.length) return log('일치하는 팩이 없습니다: ' + name);
          for (const id of ids) { const ai = getAi(id); if (ai) { ai.s = {}; saveAi(id, ai); } }
          log(`${ids.map((id) => meta[id].title).join(', ')} 의 AI 태그를 지웠습니다. '태깅 시작'을 누르세요.`);
        }
      } catch (er) {
        log('오류: ' + er.message);
      }
    });
  }

  const PROMPT = (title, alt) => `이 이미지는 한국 커뮤니티 디시인사이드의 "디시콘"(채팅용 스티커)이다. 사용자가 나중에 검색해서 이 스티커를 찾을 수 있도록 태그를 달아라.
참고: 디시콘 묶음 이름 = "${title}", 업로더가 붙인 이름 = "${alt}" (숫자이거나 의미가 없을 수도 있음).
JSON으로만 답하라.
- text: 이미지 안에 쓰인 글자를 그대로 옮겨 적기. 없으면 빈 문자열.
- emotion: 캐릭터의 감정 1~3개. 다음 중에서 고르기: 기쁨, 웃음, 신남, 슬픔, 울음, 화남, 짜증, 놀람, 당황, 부끄러움, 사랑, 귀여움, 졸림, 피곤, 무표정, 멍함, 의기양양, 비웃음, 겁먹음, 걱정, 혐오, 감동, 진지, 장난, 삐짐
- action: 동작이나 상황 0~4개 (예: 엄지척, 손인사, 먹기, 손가락질, 박수, 도망, 잠자기, 손하트, 절하기, 춤)
- keywords: 검색용 추가 키워드 5~10개. 대화에서 이 스티커를 쓸 상황(감사, 사과, 동의, 거절, 축하, 인사, 질문, 응원 등), 캐릭터 이름이나 특징, 등장하는 물건, 유행어/밈. 영어 동의어 2~3개 포함.
- desc: 이 스티커를 한 문장으로 설명 (한국어).
출력 형식: {"text": "...", "emotion": ["..."], "action": ["..."], "keywords": ["..."], "desc": "..."}`;

  const SCHEMA = {
    type: 'object',
    properties: {
      text: { type: 'string' },
      emotion: { type: 'array', items: { type: 'string' } },
      action: { type: 'array', items: { type: 'string' } },
      keywords: { type: 'array', items: { type: 'string' } },
      desc: { type: 'string' },
    },
    required: ['text', 'emotion', 'action', 'keywords', 'desc'],
  };

  async function imageB64(url) {
    const r = await gm({ method: 'GET', url, responseType: 'blob', headers: { Referer: M_ORIGIN + '/' } });
    if (r.status !== 200 || !r.response) throw new Error('이미지 받기 실패 ' + r.status);
    const src = URL.createObjectURL(r.response);
    try {
      const img = await new Promise((ok, bad) => {
        const i = new Image();
        i.onload = () => ok(i);
        i.onerror = () => bad(new Error('이미지 해석 실패'));
        i.src = src;
      });
      // Upscale small stickers a bit so text is readable; flatten transparency onto white. GIFs give their first frame.
      const sc = 448 / Math.max(img.naturalWidth, img.naturalHeight);
      const w = Math.round(img.naturalWidth * sc), h = Math.round(img.naturalHeight * sc);
      const cv = el('canvas', { width: w, height: h });
      const g = cv.getContext('2d');
      g.fillStyle = '#fff';
      g.fillRect(0, 0, w, h);
      g.drawImage(img, 0, 0, w, h);
      return cv.toDataURL('image/png').split(',')[1];
    } finally {
      URL.revokeObjectURL(src);
    }
  }

  const fatal = (msg) => Object.assign(new Error(msg), { fatal: true });
  const aiBase = () => cfg().ollama.replace(/\/+$/, '');

  // KoboldCpp silently ignores images when no mmproj is loaded, which would produce made-up tags; refuse to run then.
  async function checkAiServer() {
    if (cfg().api !== 'openai') return;
    let r;
    try {
      r = await gm({ method: 'GET', url: aiBase() + '/api/extra/version', timeout: 10000 });
    } catch {
      throw fatal('AI 서버에 연결할 수 없습니다 (' + aiBase() + '). 서버가 켜져 있는지 확인하세요.');
    }
    if (r.status !== 200) return; // not KoboldCpp (LM Studio etc.); nothing to check
    let info = {};
    try { info = JSON.parse(r.responseText); } catch { return; }
    if (info.vision === false) throw fatal('KoboldCpp에 비전 모델이 없습니다. 비전 모델(GGUF)과 짝이 맞는 mmproj 파일을 함께 불러온 뒤 다시 시도하세요.');
    log(`AI 서버: ${info.result || '?'} ${info.version || ''}${info.vision ? ' (비전 지원)' : ''}`);
  }

  function parseLooseJson(s) {
    // Reasoning models (Qwen3.x etc.) may emit a <think>…</think> block before the answer.
    s = String(s || '').replace(/<think>[\s\S]*?(<\/think>|$)/gi, '').replace(/```(?:json)?/gi, '');
    const a = s.indexOf('{'), b = s.lastIndexOf('}');
    if (a < 0 || b < a) throw new Error('AI 응답이 JSON이 아닙니다: ' + s.slice(0, 120));
    return JSON.parse(s.slice(a, b + 1));
  }

  async function aiTag(b64, title, alt) {
    const c = cfg();
    const openai = c.api === 'openai';
    const body = openai
      ? {
        // Room for a reasoning model's thinking before the JSON answer.
        model: c.model || 'local', temperature: 0.1, max_tokens: 4096,
        messages: [{
          role: 'user',
          content: [
            { type: 'text', text: PROMPT(title, alt) },
            { type: 'image_url', image_url: { url: 'data:image/png;base64,' + b64 } },
          ],
        }],
      }
      : {
        model: c.model, stream: false, format: SCHEMA, keep_alive: '30m', options: { temperature: 0.1 },
        messages: [{ role: 'user', content: PROMPT(title, alt), images: [b64] }],
      };
    let r;
    try {
      r = await gm({
        method: 'POST',
        url: aiBase() + (openai ? '/v1/chat/completions' : '/api/chat'),
        headers: { 'Content-Type': 'application/json' },
        data: JSON.stringify(body),
        timeout: 300000,
      });
    } catch (e) {
      throw fatal('AI 서버에 연결할 수 없습니다 (' + aiBase() + '). 서버가 켜져 있는지 확인하세요.');
    }
    if (!openai && r.status === 403) throw fatal('Ollama가 요청을 거부했습니다(403). 환경변수 OLLAMA_ORIGINS=* 를 설정하고 Ollama를 재시작하세요.');
    if (!openai && r.status === 404) throw fatal(`모델 "${c.model}" 이 없습니다. ollama pull ${c.model} 를 실행하세요.`);
    if (r.status !== 200) throw new Error('AI 서버 ' + r.status + ': ' + (r.responseText || '').slice(0, 160));
    const res = JSON.parse(r.responseText);
    const j = parseLooseJson(openai ? res.choices?.[0]?.message?.content : res.message?.content);
    return {
      t: String(j.text || '').trim(), e: uniq(arr(j.emotion)), k: uniq([...arr(j.action), ...arr(j.keywords)]), d: String(j.desc || '').trim(),
      m: (openai ? res.model : '') || c.model || c.api,
    };
  }

  let tagRun = null;
  async function runTagger() {
    if (tagRun) return log('이미 실행 중입니다');
    tagRun = { stop: false };
    let done = 0;
    try {
      if (syncOn()) {
        log('Nextcloud에서 최신 데이터 받는 중…');
        await syncPull(true).catch((e) => log('동기화 실패: ' + e.message));
      } else log('Nextcloud가 설정되지 않아 결과는 이 브라우저에만 저장됩니다.');
      await checkAiServer();
      log('디시콘 목록 읽는 중…');
      const packs = await fetchPackList();
      savePackMeta(packs);
      log(`팩 ${packs.length}개`);
      for (const [i, p] of packs.entries()) {
        if (tagRun.stop) break;
        const stickers = await fetchPack(p.id);
        await sleep(300);
        const ai = getAi(p.id) || { s: {} };
        Object.assign(ai, { id: p.id, title: p.title, icon: p.icon });
        for (const s of stickers) if (ai.s[s.d]) ai.s[s.d].img = s.img;
        const todo = stickers.filter((s) => !ai.s[s.d] || (ai.s[s.d].err && (ai.s[s.d].tries || 0) < 3));
        if (!todo.length) continue;
        log(`[${i + 1}/${packs.length}] ${p.title}: ${todo.length}개 태깅`);
        let changed = 0;
        for (const s of todo) {
          if (tagRun.stop) break;
          const prev = ai.s[s.d];
          try {
            const t = await aiTag(await imageB64(s.img), p.title, s.alt);
            ai.s[s.d] = { img: s.img, alt: s.alt, ...t, at: Date.now() };
            done++;
          } catch (e) {
            if (e.fatal) throw e;
            ai.s[s.d] = { img: s.img, alt: s.alt, err: e.message, tries: (prev?.tries || 0) + 1, at: Date.now() };
            log('  실패: ' + e.message);
          }
          saveAi(p.id, ai);
          if (++changed % 25 === 0) await pushPack(p.id).catch((e) => log('업로드 실패: ' + e.message));
        }
        if (changed % 25) await pushPack(p.id).catch((e) => log('업로드 실패: ' + e.message));
        dirtyIndex();
      }
      log(tagRun.stop ? `중지됨 — 이번에 태깅 ${done}개` : `완료 — 이번에 태깅 ${done}개`);
    } catch (e) {
      log('오류: ' + e.message);
    } finally {
      tagRun = null;
    }
  }

  // ---------- boot ----------
  const style = el('style');
  style.textContent = CSS;
  document.head.append(style);

  GM_registerMenuCommand('DCCon Tagger 설정 / AI 태깅', openSettings);

  const scan = () => document.querySelectorAll('.dccon-top-menu-wrap:not([data-dct])').forEach(injectMenu);
  new MutationObserver(scan).observe(document.body, { childList: true, subtree: true });
  scan();

  // PC tagger tab: open any dcinside page with #dct-tagger and leave it open; it tags new packs every hour.
  if (location.hash.includes('dct-tagger')) {
    openSettings();
    runTagger();
    setInterval(runTagger, 3600e3);
  }
})();
