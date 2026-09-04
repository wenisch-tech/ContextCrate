import Alpine from 'alpinejs';
import { createIcons, X, Menu, LayoutDashboard, Network, Files, ScanSearch, Activity,
  Settings2, KeyRound, Boxes, Shield, ArrowLeftRight, UserRound, LockKeyhole, Braces,
  LogOut, Plus, Search, ArrowLeft, ArrowRight, Sun, Moon, Github, Bug, BookOpen, UsersRound, MessageSquare } from 'lucide';

const icons = { X, Menu, LayoutDashboard, Network, Files, ScanSearch, Activity, Settings2,
  KeyRound, Boxes, Shield, ArrowLeftRight, UserRound, LockKeyhole, Braces, LogOut, Plus,
  Search, ArrowLeft, ArrowRight, Sun, Moon, Github, Bug, BookOpen, UsersRound, MessageSquare };

const storedTheme = (() => {
  try { return localStorage.getItem('contextcrate-theme'); } catch { return null; }
})();
const preferredTheme = storedTheme || (matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark');
const applyTheme = mode => {
  document.documentElement.dataset.theme = mode;
  document.documentElement.classList.toggle('light', mode === 'light');
};
applyTheme(preferredTheme);

const csrf = () => {
  const token = document.querySelector('meta[name="_csrf"]');
  const header = document.querySelector('meta[name="_csrf_header"]');
  return token && header ? { [header.content]: token.content } : {};
};

Alpine.store('shell', {
  sidebar: false,
  toggle() { this.sidebar = !this.sidebar; },
  close() { this.sidebar = false; }
});

Alpine.store('theme', {
  mode: preferredTheme,
  toggle() {
    this.mode = this.mode === 'dark' ? 'light' : 'dark';
    applyTheme(this.mode);
    try { localStorage.setItem('contextcrate-theme', this.mode); } catch { /* use the current page only */ }
  }
});

Alpine.data('wizard', (steps = 5, initial = 1) => ({
  step: Math.min(Math.max(Number(initial) || 1, 1), steps),
  steps,
  authMethod: 'NONE',
  jobMode: 'SCHEDULED',
  next() {
    const panel = this.$root.querySelector(`[data-step="${this.step}"]`);
    const invalid = panel?.querySelector(':invalid') || [...this.$root.querySelectorAll(':invalid')].find(field => field.offsetParent !== null);
    if (invalid) { invalid.reportValidity(); return; }
    this.step = Math.min(this.steps, this.step + 1);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  },
  previous() { this.step = Math.max(1, this.step - 1); },
  value(name) { const field = this.$root.elements?.namedItem(name); return field?.value || '—'; },
  fieldStep(name) {
    const groups = [
      ['name','seedUrl','ref'],
      ['allowedHost','includeUrlPatterns','excludeUrlPatterns','maxDepth','maxPages','allowSubdomains','discoverSitemaps','includePatterns','excludePatterns','maxFiles','maxFileBytes'],
      ['userAgent','contact','perHostConcurrency','minimumDelayMillis','timeoutMillis','honorRobots','maxAttempts','initialBackoffMillis','maxBodyBytes','renderMode','deduplicateContent','trustAllCertificates','gitUsername','gitToken'],
      ['authMethod','loginPageUrl','username','password','directLogin','usernameField','passwordField','submitSelector','successUrlPattern','successContentPattern','authServerUrl','realm','clientId','clientSecret'],
      ['rawRetentionDays','chunkSize','chunkOverlap','logicalIndex','contentSelector','removeSelectors']
    ];
    const found = groups.findIndex(group => group.includes(name));
    return found < 0 ? 1 : found + 1;
  },
  submit(event) {
    const invalid = event.target.querySelector(':invalid');
    if (!invalid) return;
    event.preventDefault();
    const panel = invalid.closest('[data-step]');
    this.step = panel ? Number(panel.dataset.step) : this.fieldStep(invalid.name);
    this.$nextTick(() => invalid.reportValidity());
  }
}));

Alpine.data('liveView', (crateId, runId = '') => ({
  state: null,
  connection: 'connecting',
  source: null,
  pollTimer: null,
  retryTimer: null,
  init() {
    this.connect();
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden && this.connection !== 'live') this.startPolling();
    });
  },
  get query() { return runId ? `?runId=${encodeURIComponent(runId)}` : ''; },
  connect() {
    this.connection = 'connecting';
    this.source?.close();
    this.source = new EventSource(`/api/v1/crates/${crateId}/live${this.query}`);
    this.source.addEventListener('snapshot', event => {
      this.state = JSON.parse(event.data);
      this.connection = 'live';
      this.stopPolling();
    });
    this.source.onerror = () => {
      this.source?.close();
      this.connection = 'stale';
      this.startPolling();
      clearTimeout(this.retryTimer);
      this.retryTimer = setTimeout(() => this.connect(), 30000);
    };
  },
  async refresh() {
    if (document.hidden) return;
    try {
      const response = await fetch(`/api/v1/crates/${crateId}/live/snapshot${this.query}`, { headers: { Accept: 'application/json' } });
      if (!response.ok) throw new Error();
      this.state = await response.json();
      this.connection = 'polling';
    } catch { this.connection = 'offline'; }
  },
  startPolling() {
    if (this.pollTimer) return;
    this.refresh();
    this.pollTimer = setInterval(() => this.refresh(), 5000);
  },
  stopPolling() { clearInterval(this.pollTimer); this.pollTimer = null; }
}));

Alpine.data('providerFields', (embedding, reranking, strategy) => ({
  embedding, reranking, strategy
}));

Alpine.data('asyncAction', () => ({
  busy: false, message: '', error: false,
  async post(url, form = null) {
    this.busy = true; this.error = false; this.message = 'Working…';
    try {
      const response = await fetch(url, { method: 'POST', headers: csrf(), body: form ? new FormData(form) : undefined });
      const result = await response.json();
      if (!response.ok) throw new Error(result.error || 'The operation failed.');
      this.message = result.status || 'Complete.';
    } catch (error) { this.error = true; this.message = error.message; }
    finally { this.busy = false; }
  }
}));

window.Alpine = Alpine;
document.addEventListener('alpine:initialized', () => createIcons({ icons }));
Alpine.start();

const inferredTitle = document.querySelector('main h1, main h2');
const topbarTitle = document.querySelector('[data-page-title]');
if (inferredTitle && topbarTitle && topbarTitle.textContent === 'Workspace')
  topbarTitle.textContent = inferredTitle.textContent.trim();

// Small compatibility bridge for server-rendered dialogs/tabs while their markup is migrated.
document.addEventListener('click', event => {
  const modalTrigger = event.target.closest('[data-ui-toggle="modal"]');
  if (modalTrigger) {
    const modal = document.querySelector(modalTrigger.dataset.uiTarget);
    modal?.classList.add('show');
  }
  const dismiss = event.target.closest('[data-ui-dismiss="modal"]');
  if (dismiss) dismiss.closest('.modal')?.classList.remove('show');
  const tab = event.target.closest('[data-ui-toggle="tab"], [data-ui-toggle="pill"]');
  if (tab) {
    event.preventDefault();
    document.querySelectorAll('[data-ui-toggle="tab"], [data-ui-toggle="pill"]')
      .forEach(node => node.classList.remove('active'));
    tab.classList.add('active');
    document.querySelectorAll('.tab-pane').forEach(node => node.classList.remove('show', 'active'));
    document.querySelector(tab.dataset.uiTarget)?.classList.add('show', 'active');
    history.replaceState(null, '', tab.dataset.uiTarget);
  }
});
document.addEventListener('click', event => {
  if (event.target.classList.contains('modal') && event.target.dataset.onboardingRequired !== 'true') event.target.classList.remove('show');
});

const requiredOnboardingModal = document.querySelector('.modal[data-onboarding-required="true"]');
if (requiredOnboardingModal) requiredOnboardingModal.classList.add('show');

const loginNetworkCanvas = document.getElementById('login-network-canvas');
if (loginNetworkCanvas) {
  const context = loginNetworkCanvas.getContext('2d');
  const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
  let width = 0, height = 0, frame = 0, nodes = [], packets = [], lastTime = 0;
  let seed = 0x43c0ffee;
  const random = () => ((seed = Math.imul(seed ^ seed >>> 15, 1 | seed), seed ^= seed + Math.imul(seed ^ seed >>> 7, 61 | seed), ((seed ^ seed >>> 14) >>> 0) / 4294967296));

  const createScene = () => {
    seed = 0x43c0ffee;
    const count = Math.max(14, Math.min(32, Math.round(width / 72)));
    nodes = Array.from({ length: count }, (_, index) => ({
      x: random() * width, y: (.14 + random() * .82) * height,
      vx: (random() - .5) * 7, vy: (random() - .5) * 4,
      radius: index % 7 === 0 ? 2.8 : 1.7 + random() * .7,
      accent: index % 4 === 0
    }));
    packets = Array.from({ length: Math.max(3, Math.round(width / 380)) }, (_, index) => ({
      from: index % count, to: (index * 5 + 7) % count, progress: random(), speed: .045 + random() * .035
    }));
  };

  const resizeNetwork = () => {
    const bounds = loginNetworkCanvas.getBoundingClientRect();
    const ratio = Math.min(devicePixelRatio || 1, 2);
    width = Math.max(1, bounds.width); height = Math.max(1, bounds.height);
    loginNetworkCanvas.width = Math.round(width * ratio);
    loginNetworkCanvas.height = Math.round(height * ratio);
    context.setTransform(ratio, 0, 0, ratio, 0, 0);
    createScene(); drawNetwork(0);
  };

  const connections = () => {
    const result = [], reach = Math.min(190, Math.max(115, width / 7));
    for (let first = 0; first < nodes.length; first++) for (let second = first + 1; second < nodes.length; second++) {
      const a = nodes[first], b = nodes[second], distance = Math.hypot(a.x - b.x, a.y - b.y);
      if (distance < reach) result.push({ first, second, strength: 1 - distance / reach });
    }
    return result;
  };

  function drawNetwork(delta) {
    context.clearRect(0, 0, width, height);
    if (!reducedMotion.matches) nodes.forEach(node => {
      node.x += node.vx * delta; node.y += node.vy * delta;
      if (node.x < -12 || node.x > width + 12) node.vx *= -1;
      if (node.y < height * .08 || node.y > height + 8) node.vy *= -1;
    });
    const edges = connections();
    edges.forEach(edge => {
      const a = nodes[edge.first], b = nodes[edge.second];
      context.beginPath(); context.moveTo(a.x, a.y); context.lineTo(b.x, b.y);
      context.strokeStyle = `rgba(${a.accent || b.accent ? '113,128,255' : '53,201,194'},${.08 + edge.strength * .22})`;
      context.lineWidth = .65 + edge.strength * .65; context.stroke();
    });
    nodes.forEach(node => {
      context.beginPath(); context.arc(node.x, node.y, node.radius, 0, Math.PI * 2);
      context.fillStyle = node.accent ? 'rgba(134,146,255,.82)' : 'rgba(89,222,212,.76)';
      context.shadowColor = node.accent ? '#7180ff' : '#35c9c2'; context.shadowBlur = 9; context.fill();
    });
    context.shadowBlur = 12;
    if (!reducedMotion.matches && edges.length) packets.forEach(packet => {
      packet.progress = (packet.progress + packet.speed * delta) % 1;
      const edge = edges[(packet.from + packet.to) % edges.length], a = nodes[edge.first], b = nodes[edge.second];
      const x = a.x + (b.x - a.x) * packet.progress, y = a.y + (b.y - a.y) * packet.progress;
      context.beginPath(); context.arc(x, y, 2.2, 0, Math.PI * 2); context.fillStyle = 'rgba(190,255,250,.95)'; context.shadowColor = '#7debe2'; context.fill();
    });
    context.shadowBlur = 0;
  }

  const animateNetwork = time => {
    const delta = Math.min((time - lastTime) / 1000 || 0, .04); lastTime = time;
    drawNetwork(delta);
    if (!reducedMotion.matches && !document.hidden) frame = requestAnimationFrame(animateNetwork);
  };
  const syncNetworkMotion = () => {
    cancelAnimationFrame(frame); lastTime = 0; drawNetwork(0);
    if (!reducedMotion.matches && !document.hidden) frame = requestAnimationFrame(animateNetwork);
  };
  new ResizeObserver(resizeNetwork).observe(loginNetworkCanvas);
  reducedMotion.addEventListener('change', syncNetworkMotion);
  document.addEventListener('visibilitychange', syncNetworkMotion);
  syncNetworkMotion();
}
