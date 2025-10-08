const canvas = document.getElementById('canvas');
const ctx = canvas.getContext('2d');
const size = 640;
let world = 100;
const seenEvents = new Set();
let isRunning = false;

function api(path) {
  return fetch(path, { headers: { 'Cache-Control': 'no-store' } });
}

// UI state management
function updateSimulationStatus(running) {
  isRunning = running;
  const statusDot = document.querySelector('.status-dot');
  const statusText = document.querySelector('.status-text');
  
  if (running) {
    statusDot.className = 'status-dot running';
    statusText.textContent = 'Running';
  } else {
    statusDot.className = 'status-dot stopped';
    statusText.textContent = 'Stopped';
  }
}

function updateAlertCount(count) {
  const alertCount = document.getElementById('alertCount');
  const emptyAlerts = document.getElementById('emptyAlerts');
  
  alertCount.textContent = count;
  alertCount.className = count > 0 ? 'alert-count' : 'alert-count zero';
  emptyAlerts.className = count > 0 ? 'empty-state hidden' : 'empty-state';
}

function updateLogVisibility() {
  const log = document.getElementById('log');
  const emptyLog = document.getElementById('emptyLog');
  const hasLogs = log.children.length > 0;
  
  emptyLog.className = hasLogs ? 'empty-state hidden' : 'empty-state';
}

function drawGrid() {
  // Clear canvas with dark background
  ctx.fillStyle = '#0a0a0a';
  ctx.fillRect(0, 0, size, size);
  
  // Draw subtle grid
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.05)';
  ctx.lineWidth = 1;
  const step = size / 16;
  
  for (let i = 0; i <= 16; i++) {
    ctx.beginPath();
    ctx.moveTo(0, i * step);
    ctx.lineTo(size, i * step);
    ctx.stroke();
    
    ctx.beginPath();
    ctx.moveTo(i * step, 0);
    ctx.lineTo(i * step, size);
    ctx.stroke();
  }
  
  // Add border
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.1)';
  ctx.lineWidth = 2;
  ctx.strokeRect(1, 1, size - 2, size - 2);
}

function proj(x) { return (x / world) * size; }

function drawAircraft(ac) {
  const x = proj(ac.x), y = proj(ac.y);
  let color = '#ffffff'; // default white
  let shouldBlink = false;
  let radius = 6;
  
  if (ac.status === 'critical_proximity') {
    color = '#ef4444'; // red
    radius = 8;
  } else if (ac.status === 'warning_proximity') {
    color = '#f59e0b'; // yellow
    shouldBlink = true;
    radius = 7;
  } else if (ac.status === 'conflict_course') {
    color = '#f59e0b'; // yellow blinking for conflict course
    shouldBlink = true;
    radius = 7;
  } else if (ac.status === 'deviated') {
    color = '#06b6d4'; // cyan
    radius = 6;
  }
  
  // Draw aircraft with enhanced styling
  ctx.save();
  
  if (shouldBlink) {
    const t = Date.now() % 1000;
    ctx.globalAlpha = t < 500 ? 1 : 0.3;
  }
  
  // Outer glow
  ctx.shadowColor = color;
  ctx.shadowBlur = 8;
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.arc(x, y, radius, 0, Math.PI * 2);
  ctx.fill();
  
  // Inner circle
  ctx.shadowBlur = 0;
  ctx.fillStyle = '#ffffff';
  ctx.beginPath();
  ctx.arc(x, y, radius * 0.6, 0, Math.PI * 2);
  ctx.fill();
  
  // Status indicator dot
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.arc(x, y, radius * 0.3, 0, Math.PI * 2);
  ctx.fill();
  
  ctx.restore();
  
  // Enhanced label
  ctx.fillStyle = '#ffffff';
  ctx.font = 'bold 11px Inter, sans-serif';
  ctx.textAlign = 'left';
  ctx.textBaseline = 'bottom';
  
  // Background for text
  const text = `${ac.id}`;
  const textWidth = ctx.measureText(text).width;
  ctx.fillStyle = 'rgba(0, 0, 0, 0.7)';
  ctx.fillRect(x + 10, y - 20, textWidth + 8, 14);
  
  // Text
  ctx.fillStyle = '#ffffff';
  ctx.fillText(text, x + 14, y - 8);
  
  // Status indicator
  ctx.fillStyle = color;
  ctx.font = '9px Inter, sans-serif';
  ctx.fillText(ac.status.substring(0, 3).toUpperCase(), x + 14, y + 2);
}

function render(state) {
  world = state.world;
  drawGrid();
  for (const ac of state.aircraft) drawAircraft(ac);
}

function prependLog(e) {
  if (seenEvents.has(e.id)) return;
  seenEvents.add(e.id);
  const ul = document.getElementById('log');
  const li = document.createElement('li');
  const dt = new Date(e.ts).toLocaleTimeString();
  
  // Add appropriate CSS class based on event type
  let eventClass = 'info';
  if (e.type === 'near_miss' || e.type === 'critical') {
    eventClass = 'alert';
  } else if (e.type === 'conflict_predicted' || e.type === 'warning') {
    eventClass = 'warning';
  } else if (e.type === 'avoidance_applied' || e.type === 'success') {
    eventClass = 'success';
  }
  
  li.className = eventClass;
  li.innerHTML = `
    <div class="log-time">[${dt}]</div>
    <div class="log-type">${e.type.replace('_', ' ').toUpperCase()}</div>
    <div class="log-message">${e.message}</div>
  `;
  
  if (ul.firstChild) ul.insertBefore(li, ul.firstChild); else ul.appendChild(li);
  
  // Keep only last 50 log entries
  while (ul.children.length > 50) {
    ul.removeChild(ul.lastChild);
  }
  
  updateLogVisibility();
}

function setAlerts(list) {
  const ul = document.getElementById('alerts');
  ul.innerHTML = '';
  
  for (const a of list) {
    const li = document.createElement('li');
    li.className = 'alert';
    li.innerHTML = `
      <div class="alert-aircraft">${a.a1} ↔ ${a.a2}</div>
      <div class="alert-message">${a.advisory}</div>
    `;
    ul.appendChild(li);
  }
  
  updateAlertCount(list.length);
}

async function poll() {
  try {
    const [u, g, status] = await Promise.all([
      api('/updateSimulation').then(r => r.json()),
      api('/getAlerts').then(r => r.json()),
      api('/status').then(r => r.json())
    ]);
    
    render(u);
    for (const e of u.events) prependLog(e);
    setAlerts(g.alerts || []);
    updateSimulationStatus(status.running);
  } catch (e) {
    console.error('Polling error:', e);
  }
}

// Button event handlers
document.getElementById('startBtn').onclick = async () => {
  await api('/startSimulation');
  updateSimulationStatus(true);
};

document.getElementById('stopBtn').onclick = async () => {
  await api('/stopSimulation');
  updateSimulationStatus(false);
};

document.getElementById('resetBtn').onclick = async () => {
  await api('/reset');
  // clear UI
  document.getElementById('log').innerHTML = '';
  document.getElementById('alerts').innerHTML = '';
  seenEvents.clear();
  updateSimulationStatus(false);
  updateAlertCount(0);
  updateLogVisibility();
  // fetch to get seeded state and new reset event
  poll();
};

// Clear log button
document.getElementById('clearLog').onclick = () => {
  document.getElementById('log').innerHTML = '';
  updateLogVisibility();
};

document.getElementById('applyBasic').onclick = async () => {
  const n = +document.getElementById('countInput').value;
  const tick = +document.getElementById('tickInput').value;
  await api(`/configure?n=${n}&tick=${tick}`);
};

document.getElementById('applyAdvanced').onclick = async () => {
  const n = +document.getElementById('countInput').value;
  const tick = +document.getElementById('tickInput').value;
  const world = +document.getElementById('worldInput').value;
  const sep = +document.getElementById('sepInput').value;
  await api(`/configureAdvanced?n=${n}&tick=${tick}&world=${world}&sep=${sep}`);
};

setInterval(poll, 1000);
poll();


