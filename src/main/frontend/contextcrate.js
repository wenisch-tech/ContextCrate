import Alpine from 'alpinejs';
import { createIcons, X, Menu, LayoutDashboard, Network, Files, ScanSearch, Activity,
  Settings2, KeyRound, Boxes, Shield, ArrowLeftRight, UserRound, LockKeyhole, Braces,
  LogOut, Plus, Search, ArrowLeft, ArrowRight, Sun, Moon, Github, Bug, MessageSquare } from 'lucide';

const icons = { X, Menu, LayoutDashboard, Network, Files, ScanSearch, Activity, Settings2,
  KeyRound, Boxes, Shield, ArrowLeftRight, UserRound, LockKeyhole, Braces, LogOut, Plus,
  Search, ArrowLeft, ArrowRight, Sun, Moon, Github, Bug, MessageSquare };

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
