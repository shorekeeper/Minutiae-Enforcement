/*
 * Minutiae panel — vanilla client.
 *
 * The left pane lazily expands the Hive as a registry tree; the right pane lists
 * the selected key's values in a Name/Type/Data table. Provenance is followed by
 * descending the tree — subject, measure, enforcer, sanction — rather than by
 * lateral links. A CSV value opens in a modal. A history stack backs
 * Back/Forward/Up, and the tree supports arrow-key navigation. Write actions
 * post to the action API and refresh; the management control issues a sanction
 * through the same resolver and executor as a manual command.
 */
'use strict';

const state = {
    role: null, sel: '/', expanded: new Set(['/']),
    cache: new Map(), hist: [], hix: -1, flat: []
};
const $ = (id) => document.getElementById(id);

async function api(url) {
    const r = await fetch(url, { credentials: 'same-origin' });
    if (r.status === 401) { showLogin(); throw new Error('unauthenticated'); }
    if (!r.ok) throw new Error((await r.json()).error || r.statusText);
    return r.json();
}
async function post(url, body) {
    return fetch(url, {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
    });
}
function showLogin() { $('app').classList.add('hidden'); $('login').classList.remove('hidden'); }
function showApp() { $('login').classList.add('hidden'); $('app').classList.remove('hidden'); }
function escapeHtml(s) {
    return String(s).replace(/[&<>"]/g, c =>
        ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

async function loadKey(path) {
    if (state.cache.has(path)) return state.cache.get(path);
    let key = null;
    try { key = await api('/api/key?path=' + encodeURIComponent(path)); } catch (e) { key = null; }
    state.cache.set(path, key);
    return key;
}

// navigation
function ancestors(path) {
    const out = ['/']; let acc = '';
    for (const seg of path.split('/').filter(Boolean)) { acc += '/' + seg; out.push(acc); }
    return out;
}
function parentOf(path) {
    const p = path.split('/').filter(Boolean); p.pop();
    return p.length ? '/' + p.join('/') : '/';
}
async function go(path, push = true) {
    state.sel = path;
    for (const seg of ancestors(path)) state.expanded.add(seg);
    state.expanded.add(path);
    if (push) {
        state.hist = state.hist.slice(0, state.hix + 1);
        state.hist.push(path); state.hix = state.hist.length - 1;
    }
    $('address').value = path;
    await renderTree(); await renderDetail(path); updateToolbar();
}
function back() { if (state.hix > 0) { state.hix--; go(state.hist[state.hix], false); } }
function forward() { if (state.hix < state.hist.length - 1) { state.hix++; go(state.hist[state.hix], false); } }
function up() { if (state.sel !== '/') go(parentOf(state.sel)); }
async function refresh() { state.cache.clear(); await renderTree(); await renderDetail(state.sel); }
function updateToolbar() {
    $('back').disabled = state.hix <= 0;
    $('fwd').disabled = state.hix >= state.hist.length - 1;
    $('up').disabled = state.sel === '/';
}

// tree
async function renderTree() {
    state.flat = [];
    $('tree').innerHTML = '';
    await appendNode($('tree'), '/', 'Computer', 0);
}
async function appendNode(container, path, label, depth) {
    const key = await loadKey(path);
    const hasKids = key && key.children.length > 0;
    const expanded = state.expanded.has(path);
    const row = document.createElement('div');
    row.className = 'row' + (depth === 1 ? ' hive' : '') + (path === state.sel ? ' sel' : '');
    row.style.paddingLeft = (depth * 16 + 4) + 'px';
    row.innerHTML = `<span class="tw">${hasKids ? (expanded ? '\u25BE' : '\u25B8') : ''}</span>` +
        `<span class="nm">${escapeHtml(label)}</span>`;
    row.onclick = (e) => {
        e.stopPropagation();
        if (hasKids && e.offsetX < depth * 16 + 20) toggle(path); else go(path);
    };
    container.appendChild(row);
    state.flat.push({ path, hasKids, expanded });

    if (expanded && key) {
        const kids = document.createElement('div');
        kids.className = 'children';
        container.appendChild(kids);
        for (const c of key.children) {
            const childPath = (path === '/' ? '' : path) + '/' + c.name;
            await appendNode(kids, childPath, c.label || c.name, depth + 1);
        }
    }
}
function toggle(path) {
    if (state.expanded.has(path)) state.expanded.delete(path); else state.expanded.add(path);
    renderTree();
}

// detail
async function renderDetail(path) {
    const key = await loadKey(path);
    renderCrumb(path);
    const tbody = $('values').querySelector('tbody');
    tbody.innerHTML = '';
    if (!key) {
        tbody.innerHTML = '<tr><td colspan="3" style="color:#8a8a8a">(not present)</td></tr>';
        $('actions').innerHTML = ''; setStatus(path, 0, 0); return;
    }
    for (const v of key.values) {
        const tr = document.createElement('tr');
        tr.innerHTML = `<td class="k">${escapeHtml(v.name)}</td>` +
            `<td class="t">${v.type.toLowerCase()}</td>`;
        const data = document.createElement('td');
        data.className = 'v';
        if (v.type === 'CSV') {
            const btn = document.createElement('button');
            btn.textContent = v.display;
            btn.onclick = () => openCsv(v.name, v.raw);
            data.appendChild(btn);
        } else if (v.type === 'LINK' && v.link) {
            const a = document.createElement('span');
            a.className = 'link'; a.textContent = v.display; a.onclick = () => go(v.link);
            data.appendChild(a);
        } else if (v.type === 'SCORE') {
                    const span = document.createElement('span');
                    const n = parseFloat(v.raw);
                    span.textContent = v.display;
                    span.style.color = isNaN(n) ? 'var(--fg)' : (n >= 0.7 ? '#f48771' : '#d7ba7d');
                    data.appendChild(span);
        } else {
            data.textContent = v.display;
        }
        tr.appendChild(data);
        tbody.appendChild(tr);
    }
    renderActions(path, key);
    setStatus(path, key.values.length, key.children.length);
}
function renderCrumb(path) {
    const bc = $('crumb'); bc.innerHTML = '';
    const root = document.createElement('a'); root.textContent = 'Computer';
    root.onclick = () => go('/'); bc.appendChild(root);
    let acc = '';
    for (const seg of path.split('/').filter(Boolean)) {
        acc += '/' + seg; const p = acc;
        const s = document.createElement('span'); s.className = 'sepc'; s.textContent = ' \\ ';
        bc.appendChild(s);
        const a = document.createElement('a'); a.textContent = seg; a.onclick = () => go(p);
        bc.appendChild(a);
    }
}
function setStatus(path, values, subkeys) {
    $('statPath').textContent = path;
    $('statCount').textContent = values + ' values, ' + subkeys + ' subkeys';
}
function renderActions(path, key) {
    const box = $('actions'); box.innerHTML = '';
    if (state.role !== 'write') return;
    const p = path.split('/').filter(Boolean);

    // A sanction node under the cases tree, whether reached via Sanctions,
    // Lifts, Joinders, or Weaves, whose last segment is a numeric id.
    const isSanction = p[0] === 'HKEY_CASES'
        && key.values.some(v => v.name === 'Measure')
        && /^\d+$/.test(p[p.length - 1]);
    if (isSanction) {
        const id = p[p.length - 1];
        const lifted = key.values.some(v => v.name === 'LiftedAt');
        if (!lifted) {
            const b = document.createElement('button');
            b.className = 'danger'; b.textContent = 'Lift #' + id;
            b.onclick = async () => {
                const reason = prompt('Lift reason:', ''); if (reason === null) return;
                await post('/api/action', { type: 'lift', id, reason });
                state.cache.delete(path); await refresh();
            };
            box.appendChild(b);
        } else {
            const s = document.createElement('span');
            s.style.color = '#8a8a8a'; s.textContent = 'already lifted'; box.appendChild(s);
        }
    }

    // A pending appeal under either appeals branch.
    const isAppeal = p[0] === 'HKEY_APPEALS'
        && key.values.some(v => v.name === 'Status' && v.display === 'PENDING');
    if (isAppeal) {
        const id = p[p.length - 1];
        for (const [label, type, cls] of
             [['Accept', 'appeal-accept', ''], ['Deny', 'appeal-deny', 'danger']]) {
            const b = document.createElement('button');
            b.className = cls; b.textContent = label + ' #' + id;
            b.onclick = async () => {
                const reason = prompt(label + ' reason:', ''); if (reason === null) return;
                await post('/api/action', { type, id, reason });
                state.cache.delete(path); await refresh();
            };
            box.appendChild(b);
        }
    }
}

// modal / CSV
function openCsv(title, csv) {
    $('modalTitle').textContent = title;
    const rows = parseCsv(csv);
    const t = $('modalTable'); t.innerHTML = '';
    if (rows.length === 0) {
        t.innerHTML = '<tr><td>(empty)</td></tr>';
    } else {
        const head = document.createElement('tr');
        for (const h of rows[0]) {
            const th = document.createElement('th'); th.textContent = h; head.appendChild(th);
        }
        t.appendChild(head);
        for (let r = 1; r < rows.length; r++) {
            const tr = document.createElement('tr');
            rows[r].forEach((cell, ci) => {
                const td = document.createElement('td');
                if (ci === rows[0].length - 1) td.className = 'msg';
                td.textContent = cell; tr.appendChild(td);
            });
            t.appendChild(tr);
        }
    }
    $('modal').classList.remove('hidden');
}
function parseCsv(text) {
    const rows = []; let row = []; let field = ''; let quoted = false;
    for (let i = 0; i < text.length; i++) {
        const c = text[i];
        if (quoted) {
            if (c === '"') { if (text[i + 1] === '"') { field += '"'; i++; } else quoted = false; }
            else field += c;
        } else if (c === '"') quoted = true;
        else if (c === ',') { row.push(field); field = ''; }
        else if (c === '\n') { row.push(field); rows.push(row); row = []; field = ''; }
        else if (c !== '\r') field += c;
    }
    if (field.length || row.length) { row.push(field); rows.push(row); }
    return rows.filter(r => r.length > 1 || r[0] !== '');
}
$('modalClose').onclick = () => $('modal').classList.add('hidden');
$('modal').onclick = (e) => { if (e.target === $('modal')) $('modal').classList.add('hidden'); };

// keyboard
document.addEventListener('keydown', (e) => {
    if ($('app').classList.contains('hidden')) return;
    if (!$('modal').classList.contains('hidden') && e.key === 'Escape') {
        $('modal').classList.add('hidden'); return;
    }
    if (document.activeElement && document.activeElement.tagName === 'INPUT') return;
    const idx = state.flat.findIndex(n => n.path === state.sel);
    if (idx < 0) return;
    const cur = state.flat[idx];
    switch (e.key) {
        case 'ArrowDown': e.preventDefault();
            if (idx < state.flat.length - 1) go(state.flat[idx + 1].path); break;
        case 'ArrowUp': e.preventDefault();
            if (idx > 0) go(state.flat[idx - 1].path); break;
        case 'ArrowRight': e.preventDefault();
            if (cur.hasKids && !cur.expanded) toggle(cur.path);
            else if (idx < state.flat.length - 1) go(state.flat[idx + 1].path); break;
        case 'ArrowLeft': e.preventDefault();
            if (cur.hasKids && cur.expanded) toggle(cur.path); else up(); break;
    }
});

// wiring
$('loginForm').onsubmit = async (e) => {
    e.preventDefault();
    const r = await post('/api/login', { token: $('token').value });
    if (!r.ok) { $('loginError').textContent = 'invalid token'; return; }
    const data = await r.json();
    state.role = data.role; $('role').textContent = data.role;
    if (data.role === 'write') $('manage').classList.remove('hidden');
    showApp(); await go('/');
};
$('back').onclick = back; $('fwd').onclick = forward;
$('up').onclick = up; $('reload').onclick = refresh;
$('address').onkeydown = (e) => { if (e.key === 'Enter') go($('address').value.trim() || '/'); };

$('manage').onclick = async () => {
    const spec = prompt(
        'Enforce spec:\n  Player ::toxic\n  Player @measure(MUTE) duration=1h @transcript',
        '');
    if (!spec) return;
    const r = await post('/api/action', { type: 'enforce', spec });
    if (!r.ok) { const e = await r.json(); alert('Error: ' + (e.error || r.statusText)); return; }
    await refresh();
};

let searchTimer;
$('search').oninput = () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(async () => {
        const q = $('search').value.trim(); const box = $('searchResults');
        if (!q) { box.classList.add('hidden'); return; }
        const results = await api('/api/search?q=' + encodeURIComponent(q));
        box.innerHTML = '';
        for (const r of results) {
            const d = document.createElement('div');
            d.textContent = r.label + '  (' + r.kind + ')';
            d.onclick = () => { box.classList.add('hidden'); $('search').value = ''; go(r.path); };
            box.appendChild(d);
        }
        box.classList.toggle('hidden', results.length === 0);
    }, 200);
};
document.addEventListener('click', (e) => {
    if (!$('searchResults').contains(e.target) && e.target !== $('search')) {
        $('searchResults').classList.add('hidden');
    }
});

(async () => {
    try { await api('/api/key?path=/'); state.role = 'read'; showApp(); await go('/'); }
    catch (e) { showLogin(); }
})();