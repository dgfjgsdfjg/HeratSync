// ===== HeartSync 前端：WebSocket 对话 + 记忆球 =====
const WS_URL = `ws://${location.hostname}:8081/ws/chat?token=heartsync-dev-token`;
const API_BASE = `http://${location.hostname}:8080/api`;

let ws = null;
let currentAiBubble = null;
let heartbeatTimer = null;

const messagesEl = document.getElementById('messages');
const inputEl = document.getElementById('input');
const sendBtn = document.getElementById('sendBtn');
const statusEl = document.getElementById('statusIndicator');
const statusText = document.getElementById('statusText');

/* ---------- WebSocket ---------- */
function connect() {
    ws = new WebSocket(WS_URL);

    ws.onopen = () => {
        setStatus(true);
        inputEl.disabled = false; sendBtn.disabled = false;
        addSys('已经在你身边啦～');
        clearInterval(heartbeatTimer);
        heartbeatTimer = setInterval(() => {
            if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'ping' }));
        }, 30000);
    };
    ws.onclose = () => {
        setStatus(false);
        inputEl.disabled = true; sendBtn.disabled = true;
        clearInterval(heartbeatTimer);
        addSys('断开了，5 秒后重新找你…');
        setTimeout(connect, 5000);
    };
    ws.onerror = () => setStatus(false);
    ws.onmessage = (e) => {
        try { handleMessage(JSON.parse(e.data)); } catch (err) { console.error(err); }
    };
}

function handleMessage(msg) {
    switch (msg.type) {
        case 'token':
            if (!currentAiBubble) currentAiBubble = addBubble('ai', '');
            currentAiBubble.textContent += msg.content;
            currentAiBubble.classList.add('streaming');
            scrollBottom();
            break;
        case 'done':
            if (currentAiBubble) { currentAiBubble.classList.remove('streaming'); currentAiBubble = null; }
            break;
        case 'push':
            addBubble('push', msg.content);
            break;
        case 'pong': break;
    }
}

function sendMessage() {
    const text = inputEl.value.trim();
    if (!text || !ws || ws.readyState !== WebSocket.OPEN) return;
    addBubble('user', text);
    inputEl.value = '';
    ws.send(JSON.stringify({ type: 'chat', content: text }));
}

/* ---------- UI ---------- */
function addBubble(kind, text) {
    const wrap = document.createElement('div');
    wrap.className = 'msg ' + kind;
    const b = document.createElement('div');
    b.className = 'bubble';
    b.textContent = text;
    wrap.appendChild(b);
    messagesEl.appendChild(wrap);
    scrollBottom();
    return b;
}
function addSys(text) {
    const wrap = document.createElement('div');
    wrap.className = 'msg sys';
    wrap.innerHTML = `<div class="bubble"></div>`;
    wrap.querySelector('.bubble').textContent = text;
    messagesEl.appendChild(wrap);
    scrollBottom();
}
function setStatus(online) {
    statusText.textContent = online ? '在线陪你' : '离线';
    statusEl.className = 'status ' + (online ? 'online' : 'offline');
}
function scrollBottom() { messagesEl.scrollTop = messagesEl.scrollHeight; }

sendBtn.addEventListener('click', sendMessage);
inputEl.addEventListener('keydown', (e) => { if (e.key === 'Enter') sendMessage(); });

/* ---------- 记忆球 ---------- */
const overlay = document.getElementById('ballOverlay');
const GROUP_COLOR = {
    user:    { bg: '#8fb8e8', border: '#6f9fd8' },
    lover:   { bg: '#eaa0b8', border: '#e2789a' },
    other:   { bg: '#9dd6b6', border: '#7cc59d' },
    event:   { bg: '#f2c795', border: '#e6ad6e' },
    detail:  { bg: '#d9ccee', border: '#c3b0e2' },
    persona: { bg: '#f4b8c6', border: '#e89aad' },
    state:   { bg: '#bcd6ce', border: '#9cc0b5' },
};
let network = null;

document.getElementById('openBall').addEventListener('click', openBall);
document.getElementById('closeBall').addEventListener('click', closeBall);
overlay.addEventListener('click', (e) => { if (e.target === overlay) closeBall(); });

async function openBall() {
    overlay.classList.add('active');
    try {
        const res = await fetch(`${API_BASE.replace('/api','')}/api/memory-graph`);
        const data = await res.json();
        renderGraph(data);
    } catch (e) {
        document.getElementById('ballTip').textContent = '记忆加载失败了…';
    }
}
function closeBall() { overlay.classList.remove('active'); }

function renderGraph(data) {
    const nodes = (data.nodes || []).map(n => {
        const c = GROUP_COLOR[n.group] || GROUP_COLOR.detail;
        const isHub = n.group !== 'detail';
        return {
            id: n.id, label: n.label, value: n.size || 12,
            shape: 'dot',
            data_path: n.data_path || '',  // 文件路径，用于编辑
            color: { background: c.bg, border: c.border,
                     highlight: { background: c.bg, border: c.border } },
            font: { color: isHub ? '#4A4048' : '#8a7f86',
                    size: isHub ? 16 : 12, face: 'Noto Sans SC',
                    strokeWidth: 4, strokeColor: '#ffffff' },
        };
    });
    const edges = (data.edges || []).map(e => ({
        from: e.from, to: e.to,
        color: { color: 'rgba(180,160,175,0.35)', highlight: '#eaa0b8' },
        width: 1.4, smooth: { type: 'continuous' },
    }));

    const container = document.getElementById('ballGraph');
    if (network) network.destroy();
    network = new vis.Network(container, { nodes, edges }, {
        physics: {
            barnesHut: { gravitationalConstant: -4000, springLength: 110, springConstant: 0.03, damping: 0.5 },
            stabilization: { iterations: 180 },
        },
        interaction: { hover: true, tooltipDelay: 120 },
        nodes: { borderWidth: 2, shadow: { enabled: true, color: 'rgba(120,90,100,0.15)', size: 8, y: 3 } },
    });
    network.on('click', (params) => {
        const tip = document.getElementById('ballTip');
        if (params.nodes.length) {
            const n = nodes.find(x => x.id === params.nodes[0]);
            if (n && n.id && n.id.startsWith('page:')) {
                openMemDetail(n);
            } else {
                tip.textContent = '💛 ' + (n ? n.label : '');
            }
        } else {
            tip.textContent = '拖动查看 · 滚轮缩放 · 点一颗看详情';
        }
    });
}

/* ---------- 记忆详情编辑 ---------- */
let currentMemNode = null;
let currentMemPage = null;

async function openMemDetail(node) {
    const title = node.label;
    const overlay = document.getElementById('memDetailOverlay');
    document.getElementById('memDetailTitle').textContent = title;
    document.getElementById('memEditTitle').value = title;
    document.getElementById('memEditContent').value = '加载中…';
    document.getElementById('memEditContent').disabled = true;
    document.getElementById('btnEditMem').style.display = 'inline-block';
    document.getElementById('btnSaveMem').style.display = 'none';
    document.getElementById('btnCancelEdit').style.display = 'none';
    document.getElementById('btnDeleteMem').style.display = 'inline-block';

    currentMemNode = node;
    overlay.classList.add('active');

    // 加载内容 — 用文件路径调 API，不 encode（路径含 /）
    const fetchPath = node.data_path || title;
    try {
        const res = await fetch(`${API_BASE}/memories/${fetchPath}`);
        if (res.ok) {
            currentMemPage = await res.json();
            document.getElementById('memEditContent').value = currentMemPage.content || '';
        } else {
            document.getElementById('memEditContent').value = '（暂无内容）';
        }
    } catch (e) {
        document.getElementById('memEditContent').value = '加载失败';
    }
}

function enterEditMode() {
    document.getElementById('memEditContent').disabled = false;
    document.getElementById('memEditTitle').disabled = false;
    document.getElementById('btnEditMem').style.display = 'none';
    document.getElementById('btnDeleteMem').style.display = 'none';
    document.getElementById('btnSaveMem').style.display = 'inline-block';
    document.getElementById('btnCancelEdit').style.display = 'inline-block';
}

function cancelEdit() {
    document.getElementById('memEditContent').disabled = true;
    document.getElementById('memEditTitle').disabled = true;
    document.getElementById('memEditContent').value = currentMemPage ? (currentMemPage.content || '') : '';
    document.getElementById('memEditTitle').value = currentMemPage ? (currentMemPage.title || '') : '';
    document.getElementById('btnEditMem').style.display = 'inline-block';
    document.getElementById('btnDeleteMem').style.display = 'inline-block';
    document.getElementById('btnSaveMem').style.display = 'none';
    document.getElementById('btnCancelEdit').style.display = 'none';
}

async function saveMemory() {
    if (!currentMemPage) return;
    const newTitle = document.getElementById('memEditTitle').value.trim();
    const newContent = document.getElementById('memEditContent').value.trim();
    if (!newTitle) { alert('标题不能为空'); return; }

    const path = currentMemPage.path || (`facts/${newTitle}.md`);
    try {
        const res = await fetch(`${API_BASE}/memories/${path}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title: newTitle, content: newContent })
        });
        if (res.ok) {
            currentMemPage = await res.json();
            document.getElementById('memDetailTitle').textContent = newTitle;
            document.getElementById('memEditContent').disabled = true;
            document.getElementById('memEditTitle').disabled = true;
            document.getElementById('btnEditMem').style.display = 'inline-block';
            document.getElementById('btnDeleteMem').style.display = 'inline-block';
            document.getElementById('btnSaveMem').style.display = 'none';
            document.getElementById('btnCancelEdit').style.display = 'none';
        }
    } catch (e) {
        alert('保存失败');
    }
}

async function deleteMemory() {
    if (!currentMemPage) return;
    if (!confirm(`确定删除「${currentMemPage.title}」的所有记忆吗？`)) return;

    const path = currentMemPage.path || (`facts/${currentMemPage.title}.md`);
    try {
        const res = await fetch(`${API_BASE}/memories/${path}`, { method: 'DELETE' });
        if (res.ok) {
            document.getElementById('memDetailOverlay').classList.remove('active');
            currentMemNode = null; currentMemPage = null;
            openBall(); // 刷新图谱
        }
    } catch (e) {
        alert('删除失败');
    }
}

document.getElementById('closeMemDetail').addEventListener('click', () => {
    document.getElementById('memDetailOverlay').classList.remove('active');
});
document.getElementById('memDetailOverlay').addEventListener('click', (e) => {
    if (e.target === e.currentTarget)
        document.getElementById('memDetailOverlay').classList.remove('active');
});
document.getElementById('btnEditMem').addEventListener('click', enterEditMode);
document.getElementById('btnCancelEdit').addEventListener('click', cancelEdit);
document.getElementById('btnSaveMem').addEventListener('click', saveMemory);
document.getElementById('btnDeleteMem').addEventListener('click', deleteMemory);

/* ---------- 启动 ---------- */
connect();
