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


// "Create a crate" dialog: name and description, and, when the administrator allows it, a dedicated S3 storage
// and/or search backend with a connection test for each. Talks to the REST API with the session cookie.
const SEARCH_BACKENDS = {
  opensearch: { label: 'OpenSearch', endpoint: 'https://opensearch.example.com:9200', hint: 'Base URL of the cluster.', auth: 'basic', prefixHint: 'Index names start with this prefix.' },
  elasticsearch: { label: 'Elasticsearch', endpoint: 'https://elasticsearch.example.com:9200', hint: 'Base URL of the cluster (Elasticsearch 8 or newer).', auth: 'key-or-basic', prefixHint: 'Index names start with this prefix.' },
  qdrant: { label: 'Qdrant', endpoint: 'https://qdrant.example.com:6333', hint: 'Base URL of the REST API (Qdrant 1.10 or newer).', auth: 'key', prefixHint: 'Collection names start with this prefix.' },
  pgvector: { label: 'PostgreSQL with pgvector', endpoint: 'jdbc:postgresql://db.example.com:5432/contextcrate', hint: 'JDBC URL of a database that has the pgvector extension.', auth: 'basic', prefixHint: 'Lowercase letters, digits and underscores, up to 20 characters.' }
};

Alpine.data('createCrate', (config = {}) => ({
  allowStorage: !!config.allowStorage, allowSearch: !!config.allowSearch, searchBackends: SEARCH_BACKENDS,
  name: '', description: '',
  storage: { mode: 'GLOBAL', endpoint: '', region: 'us-east-1', bucket: '', prefix: '', accessKey: '', secretKey: '', pathStyle: true },
  search: { mode: 'GLOBAL', backend: 'elasticsearch', endpoint: '', apiKey: '', username: '', password: '', prefix: '', trust: false },
  tests: { storage: null, searchBackend: null }, testing: { storage: false, searchBackend: false },
  failure: { storage: '', searchBackend: '' }, creating: false, formError: '',
  get hasChoices() { return this.allowStorage || this.allowSearch; },
  get backend() { return this.searchBackends[this.search.backend]; },
  get storageReady() { const s = this.storage; return !!(s.bucket.trim() && s.accessKey.trim() && s.secretKey); },
  get searchReady() {
    const s = this.search;
    if (!s.endpoint.trim()) return false;
    return s.backend !== 'pgvector' || !!s.username.trim();
  },
  get prefixValid() { return this.search.backend !== 'pgvector' || !this.search.prefix || /^[a-z][a-z0-9_]{0,19}$/.test(this.search.prefix); },
  get canCreate() {
    if (!this.name.trim() || this.creating) return false;
    if (this.storage.mode === 'S3' && !this.storageReady) return false;
    if (this.search.mode === 'CUSTOM' && !(this.searchReady && this.prefixValid)) return false;
    return true;
  },
  // Another backend needs another endpoint and other credentials, so the fields of the previous one are cleared.
  backendChanged() {
    Object.assign(this.search, { endpoint: '', apiKey: '', username: '', password: '', prefix: '', trust: false });
    this.stale('searchBackend');
  },
  // A changed field makes an earlier test result stale.
  stale(kind) { this.tests[kind] = null; this.failure[kind] = ''; },
  storagePayload() {
    const s = this.storage;
    return { mode: s.mode, endpoint: s.endpoint, region: s.region, bucket: s.bucket, prefix: s.prefix, accessKey: s.accessKey, secretKey: s.secretKey, pathStyle: s.pathStyle };
  },
  searchPayload() {
    const s = this.search;
    return { mode: s.mode, backend: s.backend, endpoint: s.endpoint, apiKey: s.apiKey, username: s.username, password: s.password, prefix: s.prefix, trustAllCertificates: s.trust };
  },
  async request(url, body) {
    const response = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify(body) });
    let result = {};
    try { result = await response.json(); } catch { /* an empty or non-JSON answer */ }
    return { ok: response.ok, status: response.status, result };
  },
  async test(kind) {
    this.testing[kind] = true; this.tests[kind] = null; this.failure[kind] = '';
    try {
      const url = kind === 'storage' ? '/api/v1/crates/test-storage' : '/api/v1/crates/test-search-backend';
      const { ok, result } = await this.request(url, kind === 'storage' ? this.storagePayload() : this.searchPayload());
      if (ok) this.tests[kind] = result;
      else this.failure[kind] = result.error || 'The test could not be run.';
    } catch { this.failure[kind] = 'The server could not be reached. Check your connection and try again.'; }
    finally {
      this.testing[kind] = false;
      // The result can land below the fold of the scrolling dialog body.
      this.$nextTick(() => this.$refs[kind === 'storage' ? 'storageResult' : 'searchResult']?.scrollIntoView({ block: 'nearest' }));
    }
  },
  async create() {
    if (!this.canCreate) return;
    this.creating = true; this.formError = ''; this.failure.storage = ''; this.failure.searchBackend = '';
    const body = { name: this.name.trim(), description: this.description.trim() };
    if (this.storage.mode === 'S3') body.storage = this.storagePayload();
    if (this.search.mode === 'CUSTOM') body.searchBackend = this.searchPayload();
    try {
      const { ok, status, result } = await this.request('/api/v1/crates', body);
      if (ok) { window.location.assign(`/crates/${result.id}`); return; }
      if (status === 422 && result.field) {
        // The steps below name the failed check; the message above only has to say what happened to the crate.
        this.failure[result.field] = result.steps
          ? `The crate was not created because the ${result.field === 'storage' ? 'storage' : 'search backend'} test failed. Fix the step marked in red and try again.`
          : result.error;
        this.tests[result.field] = result.steps ? { ok: false, steps: result.steps } : null;
        this.$nextTick(() => this.$refs[result.field === 'storage' ? 'storageResult' : 'searchResult']?.focus());
      } else {
        this.formError = status === 403 ? 'You are not allowed to create crates.' : (result.error || 'The crate could not be created.');
      }
    } catch { this.formError = 'The server could not be reached. Check your connection and try again.'; }
    this.creating = false;
  }
}));

// Re-tests the dedicated backends of an existing crate (crate settings page).
Alpine.data('backendRetest', crateId => ({
  busy: false, error: '', results: null,
  async run() {
    this.busy = true; this.error = ''; this.results = null;
    try {
      const response = await fetch(`/api/v1/crates/${crateId}/backends/test`, { method: 'POST', headers: { Accept: 'application/json' } });
      const result = await response.json();
      if (!response.ok) throw new Error(result.error || 'The test could not be run.');
      this.results = result;
    } catch (e) { this.error = e.message || 'The test could not be run.'; }
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
    modal?.querySelector('[data-autofocus], input:not([type=hidden]), textarea')?.focus();
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

document.addEventListener('keydown', event => {
  if (event.key !== 'Escape') return;
  document.querySelectorAll('.modal.show').forEach(modal => { if (modal.dataset.onboardingRequired !== 'true') modal.classList.remove('show'); });
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
