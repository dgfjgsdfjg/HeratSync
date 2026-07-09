// 配置（从页面 URL 推断或硬编码）
const WS_URL = `ws://${location.hostname}:8081/ws/chat?token=heartsync-dev-token`;
const API_BASE = `http://${location.hostname}:8080/api`;

let ws = null;
let currentAiBubble = null;

// DOM 元素
const messagesEl = document.getElementById('messages');
const inputEl = document.getElementById('input');
const sendBtn = document.getElementById('sendBtn');
const statusEl = document.getElementById('statusIndicator');
const memoryListEl = document.getElementById('memoryList');
const sidebarEl = document.getElementById('sidebar');
const toggleBtn = document.getElementById('toggleSidebar');

// 侧栏开关
toggleBtn.addEventListener('click', () => {
    sidebarEl.classList.toggle('collapsed');
    toggleBtn.textContent = sidebarEl.classList.contains('collapsed') ? '▶' : '◀';
});

// 连接 WebSocket
function connect() {
    ws = new WebSocket(WS_URL);

    ws.onopen = () => {
        setStatus(true);
        inputEl.disabled = false;
        sendBtn.disabled = false;
        addSystemMessage('已连接');
    };

    ws.onclose = () => {
        setStatus(false);
        inputEl.disabled = true;
        sendBtn.disabled = true;
        addSystemMessage('连接断开，5 秒后重连...');
        setTimeout(connect, 5000);
    };

    ws.onerror = () => {
        setStatus(false);
    };

    ws.onmessage = (event) => {
        try {
            const msg = JSON.parse(event.data);
            handleMessage(msg);
        } catch (e) {
            console.error('消息解析失败:', e);
        }
    };
}

// 处理服务端消息
function handleMessage(msg) {
    switch (msg.type) {
        case 'token':
            if (!currentAiBubble) {
                currentAiBubble = addAiBubble('');
            }
            currentAiBubble.textContent += msg.content;
            currentAiBubble.classList.add('streaming');
            scrollToBottom();
            break;

        case 'done':
            if (currentAiBubble) {
                currentAiBubble.classList.remove('streaming');
                currentAiBubble = null;
            }
            break;

        case 'push':
            addAiBubble(msg.content);
            break;

        case 'pong':
            // 心跳回复，无需处理
            break;
    }
}

// 发送消息
function sendMessage() {
    const text = inputEl.value.trim();
    if (!text || !ws || ws.readyState !== WebSocket.OPEN) return;

    // 显示用户消息
    addUserBubble(text);
    inputEl.value = '';

    // 发送
    ws.send(JSON.stringify({ type: 'chat', content: text }));
}

// UI 辅助
function addUserBubble(text) {
    const div = document.createElement('div');
    div.className = 'message user';
    div.innerHTML = `<div class="bubble">${escapeHtml(text)}</div>`;
    messagesEl.appendChild(div);
    scrollToBottom();
}

function addAiBubble(text) {
    const div = document.createElement('div');
    div.className = 'message ai';
    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    bubble.textContent = text;
    div.appendChild(bubble);
    messagesEl.appendChild(div);
    scrollToBottom();
    return bubble;
}

function addSystemMessage(text) {
    const div = document.createElement('div');
    div.className = 'message system';
    div.innerHTML = `<div class="bubble">${escapeHtml(text)}</div>`;
    messagesEl.appendChild(div);
    scrollToBottom();
}

function setStatus(online) {
    statusEl.textContent = online ? '● 在线' : '● 离线';
    statusEl.className = 'status ' + (online ? 'online' : 'offline');
}

function scrollToBottom() {
    messagesEl.scrollTop = messagesEl.scrollHeight;
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// 加载记忆列表
async function loadMemories() {
    try {
        const res = await fetch(`${API_BASE}/memories`);
        const memories = await res.json();
        memoryListEl.innerHTML = memories.map(m =>
            `<div class="memory-item">
                <span class="type">${m.type}</span>
                <span>${m.title}</span>
            </div>`
        ).join('');
    } catch (e) {
        memoryListEl.innerHTML = '<div class="loading">加载失败</div>';
    }
}

// 事件绑定
sendBtn.addEventListener('click', sendMessage);
inputEl.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') sendMessage();
});

// 启动
connect();
loadMemories();
