import Graph from 'graphology';
import Sigma from 'sigma';
import forceAtlas2 from 'graphology-layout-forceatlas2';
import FA2Layout from 'graphology-layout-forceatlas2/worker';

// Categorical palette for communities; tuned to stay readable on the dark and the light theme.
const PALETTE = ['#6366f1', '#22d3ee', '#f59e0b', '#10b981', '#ec4899', '#84cc16', '#ef4444', '#a855f7',
  '#14b8a6', '#f97316', '#3b82f6', '#eab308', '#d946ef', '#06b6d4', '#8b5cf6', '#64748b'];
// Opaque colours: semi-transparent ones blend towards white in WebGL and look washed out on the light theme.
const NEUTRAL = '#94a3b8';
const FADED_DARK = '#232c3d';
const FADED_LIGHT = '#dfe5ee';

const reducedMotion = () => matchMedia('(prefers-reduced-motion: reduce)').matches;
const isLight = () => document.documentElement.dataset.theme === 'light';
const token = (name, fallback) => getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback;
/** Hover/selection label in the page's panel colours (sigma's default is a white box, unreadable on the dark theme). */
function drawHover(context, data, settings) {
  const size = settings.labelSize;
  context.font = `${settings.labelWeight} ${size}px ${settings.labelFont}`;
  const label = data.label || '';
  const ring = token('--color-brand-500', '#6366f1');
  context.beginPath();
  context.arc(data.x, data.y, data.size + 3, 0, Math.PI * 2);
  context.fillStyle = ring;
  context.fill();
  if (!label) return;
  const width = context.measureText(label).width + 14, height = size + 10;
  const x = data.x + data.size + 6, y = data.y - height / 2;
  context.beginPath();
  context.roundRect(x, y, width, height, 6);
  context.fillStyle = token('--color-ink-900', '#0f172a');
  context.shadowColor = 'rgba(0, 0, 0, 0.35)';
  context.shadowBlur = 8;
  context.fill();
  context.shadowBlur = 0;
  context.lineWidth = 1;
  context.strokeStyle = ring;
  context.stroke();
  context.fillStyle = token('--color-ink-100', '#e8eef8');
  context.fillText(label, x + 7, data.y + size / 3);
}
const csrf = () => {
  const t = document.querySelector('meta[name="_csrf"]');
  const h = document.querySelector('meta[name="_csrf_header"]');
  return t && h ? { [h.content]: t.content } : {};
};

/**
 * Knowledge graph explorer: WebGL graph (sigma.js) with ForceAtlas2 layout, colour by community per hierarchy level,
 * zoom controls, search, type filter and a detail panel for entities and communities.
 */
export function knowledgeGraph(crateId) {
  // Not reactive on purpose: Alpine must not wrap the renderer or the graph in proxies.
  let renderer = null, graph = null, layout = null, layoutTimer = null, themeObserver = null;
  let highlight = null; // Set of node ids to keep in colour, or null
  let hovered = null;
  let colourTable = null; // community id -> colour, built once from the loaded communities

  return {
    loading: true,
    error: null,
    status: null,
    truncated: false,
    totalNodes: 0,
    nodeCount: 0,
    edgeCount: 0,
    levels: 0,
    level: 0,
    query: '',
    matches: [],
    types: [],
    communities: [],
    communityIndex: {},
    selected: null, // {kind: 'entity'|'community', data}
    detailLoading: false,
    layoutRunning: false,
    rebuildState: '',

    async init() {
      try {
        const response = await fetch(`/api/v1/crates/${crateId}/graph`, { headers: { Accept: 'application/json' } });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const data = await response.json();
        this.status = data.status;
        this.truncated = data.truncated;
        this.totalNodes = data.totalNodes;
        this.levels = data.levels;
        this.level = 0; // the coarsest level covers every node, so all colours appear in the legend
        this.communities = data.communities;
        this.communityIndex = Object.fromEntries(data.communities.map(c => [c.id, c]));
        if (data.nodes.length) this.$nextTick(() => this.draw(data));
      } catch (e) {
        this.error = 'The knowledge graph could not be loaded.';
      } finally {
        this.loading = false;
      }
    },

    destroy() {
      if (layoutTimer) clearTimeout(layoutTimer);
      if (layout) layout.kill();
      if (renderer) renderer.kill();
      if (themeObserver) themeObserver.disconnect();
    },

    draw(data) {
      graph = new Graph({ type: 'undirected', multi: false });
      const maxSize = Math.max(1, ...data.nodes.map(n => n.size));
      const typeCounts = {};
      // Start nodes near their top-level community, so the layout settles quickly into visible clusters.
      const anchors = {};
      data.nodes.forEach((n, i) => {
        const top = n.communities[0] || `none-${i % 7}`;
        if (!anchors[top]) { const a = Object.keys(anchors).length * 2.399963; anchors[top] = { x: Math.cos(a) * (10 + Object.keys(anchors).length), y: Math.sin(a) * (10 + Object.keys(anchors).length) }; }
        typeCounts[n.type] = (typeCounts[n.type] || 0) + 1;
        graph.addNode(n.id, {
          label: n.name, entityType: n.type, chunks: n.size, communities: n.communities,
          x: anchors[top].x + (Math.random() - 0.5) * 4, y: anchors[top].y + (Math.random() - 0.5) * 4,
          size: 3 + 14 * Math.sqrt(n.size / maxSize), color: PALETTE[0]
        });
      });
      const maxWeight = Math.max(1, ...data.edges.map(e => e.weight));
      data.edges.forEach(e => {
        if (graph.hasNode(e.source) && graph.hasNode(e.target) && !graph.hasEdge(e.source, e.target))
          graph.addEdgeWithKey(e.id, e.source, e.target, { weight: e.weight, size: 0.5 + 3 * (e.weight / maxWeight) });
      });
      this.nodeCount = graph.order;
      this.edgeCount = graph.size;
      this.types = Object.entries(typeCounts).sort((a, b) => b[1] - a[1]).map(([type, count]) => ({ type, count, on: true }));
      this.colourByLevel();

      const container = this.$refs.canvas;
      renderer = new Sigma(graph, container, {
        renderEdgeLabels: false,
        labelRenderedSizeThreshold: 7,
        labelDensity: 0.6,
        labelFont: 'Inter, ui-sans-serif, system-ui, sans-serif',
        labelSize: 12,
        labelWeight: '500',
        labelColor: { color: this.labelColour() },
        defaultEdgeColor: this.edgeColour(),
        zIndex: true,
        defaultDrawNodeHover: drawHover,
        minCameraRatio: 0.03,
        maxCameraRatio: 8,
        nodeReducer: (node, attrs) => this.reduceNode(node, attrs),
        edgeReducer: (edge, attrs) => this.reduceEdge(edge, attrs)
      });
      renderer.on('clickNode', ({ node }) => this.selectEntity(node));
      renderer.on('clickStage', () => this.clearSelection());
      renderer.on('enterNode', ({ node }) => { hovered = node; container.style.cursor = 'pointer'; renderer.refresh({ skipIndexation: true }); });
      renderer.on('leaveNode', () => { hovered = null; container.style.cursor = ''; renderer.refresh({ skipIndexation: true }); });
      themeObserver = new MutationObserver(() => {
        renderer.setSetting('labelColor', { color: this.labelColour() });
        renderer.setSetting('defaultEdgeColor', this.edgeColour());
        renderer.refresh();
      });
      themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme', 'class'] });
      this.runLayout();
    },

    runLayout() {
      if (!graph || graph.order === 0) return;
      const settings = { ...forceAtlas2.inferSettings(graph), barnesHutOptimize: graph.order > 500, adjustSizes: false, gravity: 1, scalingRatio: 8, edgeWeightInfluence: 1 };
      if (reducedMotion() || graph.order < 30) {
        forceAtlas2.assign(graph, { iterations: Math.min(600, 150 + graph.order), settings, getEdgeWeight: 'weight' });
        renderer?.refresh();
        return;
      }
      if (layout) layout.kill();
      layout = new FA2Layout(graph, { settings, getEdgeWeight: 'weight' });
      layout.start();
      this.layoutRunning = true;
      layoutTimer = setTimeout(() => this.stopLayout(), Math.min(12000, 2500 + graph.order * 4));
    },

    stopLayout() {
      if (layout) { layout.stop(); }
      this.layoutRunning = false;
    },

    toggleLayout() { this.layoutRunning ? this.stopLayout() : this.runLayout(); },

    // The ink scale is inverted by the light theme, so ink-100 is the main text colour in both.
    labelColour() { return token('--color-ink-100', isLight() ? '#172033' : '#e8eef8'); },
    edgeColour() { return isLight() ? '#c3ccda' : '#344158'; },

    reduceNode(node, attrs) {
      const out = { ...attrs };
      const typeOn = this.types.find(t => t.type === attrs.entityType)?.on !== false;
      if (!typeOn) { out.hidden = true; return out; }
      if (highlight && !highlight.has(node)) { out.color = isLight() ? FADED_LIGHT : FADED_DARK; out.label = ''; out.zIndex = 0; }
      else if (highlight) { out.zIndex = 1; out.forceLabel = highlight.size <= 40; }
      if (node === hovered || (this.selected?.kind === 'entity' && this.selected.data.id === node)) { out.highlighted = true; out.forceLabel = true; out.zIndex = 2; }
      return out;
    },

    reduceEdge(edge, attrs) {
      const out = { ...attrs };
      const [s, t] = graph.extremities(edge);
      const focus = hovered || (this.selected?.kind === 'entity' ? this.selected.data.id : null);
      if (highlight && !(highlight.has(s) && highlight.has(t))) out.hidden = true;
      else if (focus && (s === focus || t === focus)) { out.color = isLight() ? '#6366f1' : '#a5b4fc'; out.zIndex = 1; }
      return out;
    },

    /** Colours every node by its community on the selected level (falling back to the coarsest one it has). */
    colourByLevel() {
      if (!graph) return;
      graph.forEachNode((node, attrs) => {
        // The node's community on this level; nodes whose community was not split further stay neutral.
        const community = (attrs.communities || []).find(id => this.communityIndex[id]?.level === this.level);
        graph.setNodeAttribute(node, 'color', community ? this.communityColour(community) : NEUTRAL);
      });
      renderer?.refresh();
    },

    /** One colour per community, by size within its level (ties by title), shared by the graph and the legend. */
    communityColour(id) {
      if (!colourTable) {
        colourTable = {};
        const levels = [...new Set(this.communities.map(c => c.level))];
        for (const level of levels)
          this.communities.filter(c => c.level === level).sort((a, b) => b.size - a.size || a.title.localeCompare(b.title))
            .forEach((c, i) => { colourTable[c.id] = PALETTE[i % PALETTE.length]; });
      }
      return colourTable[id] || NEUTRAL;
    },

    setLevel(value) { this.level = Number(value); this.colourByLevel(); },

    get levelCommunities() {
      return this.communities.filter(c => c.level === this.level).sort((a, b) => b.size - a.size || a.title.localeCompare(b.title));
    },

    toggleType(type) {
      const t = this.types.find(x => x.type === type);
      if (t) t.on = !t.on;
      renderer?.refresh();
    },

    allTypes(on) { this.types.forEach(t => { t.on = on; }); renderer?.refresh(); },

    search() {
      const q = this.query.trim().toLowerCase();
      if (!q || !graph) { this.matches = []; return; }
      const found = [];
      graph.forEachNode((node, attrs) => { if (attrs.label.toLowerCase().includes(q)) found.push({ id: node, name: attrs.label, type: attrs.entityType, chunks: attrs.chunks }); });
      this.matches = found.sort((a, b) => (a.name.toLowerCase().startsWith(q) ? 0 : 1) - (b.name.toLowerCase().startsWith(q) ? 0 : 1) || b.chunks - a.chunks).slice(0, 8);
    },

    pickMatch(id) { this.matches = []; this.query = ''; this.selectEntity(id); },

    zoomIn() { renderer?.getCamera().animatedZoom({ duration: reducedMotion() ? 0 : 300 }); },
    zoomOut() { renderer?.getCamera().animatedUnzoom({ duration: reducedMotion() ? 0 : 300 }); },
    resetView() { renderer?.getCamera().animatedReset({ duration: reducedMotion() ? 0 : 400 }); },

    /** Moves the camera so that the given nodes fill the view. */
    focus(nodes) {
      if (!renderer || !nodes.length) return;
      const points = nodes.map(n => renderer.getNodeDisplayData(n)).filter(Boolean);
      if (!points.length) return;
      const xs = points.map(p => p.x), ys = points.map(p => p.y);
      const minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys);
      const ratio = Math.max(0.06, Math.min(1.2, Math.max(maxX - minX, maxY - minY) * 1.4));
      renderer.getCamera().animate({ x: (minX + maxX) / 2, y: (minY + maxY) / 2, ratio: points.length === 1 ? 0.25 : ratio }, { duration: reducedMotion() ? 0 : 500 });
    },

    async selectEntity(id) {
      if (!graph?.hasNode(id)) return;
      highlight = new Set([id, ...graph.neighbors(id)]);
      this.selected = { kind: 'entity', data: { id, name: graph.getNodeAttribute(id, 'label'), type: graph.getNodeAttribute(id, 'entityType'), neighbours: [], chunks: [], communities: [] } };
      renderer.refresh({ skipIndexation: true });
      this.focus([...highlight]); // the entity together with its neighbours
      await this.loadDetail(`/api/v1/crates/${crateId}/graph/entities/${id}`, 'entity');
    },

    async selectCommunity(id) {
      highlight = null;
      this.selected = { kind: 'community', data: { id, title: this.communityIndex[id]?.title || '', members: [], children: [] } };
      await this.loadDetail(`/api/v1/crates/${crateId}/graph/communities/${id}`, 'community');
      if (this.selected?.kind === 'community' && graph) {
        const members = this.selected.data.members.map(m => m.id).filter(m => graph.hasNode(m));
        highlight = new Set(members);
        renderer?.refresh({ skipIndexation: true });
        this.focus(members);
      }
    },

    async loadDetail(url, kind) {
      this.detailLoading = true;
      try {
        const response = await fetch(url, { headers: { Accept: 'application/json' } });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const data = await response.json();
        if (this.selected?.kind === kind && this.selected.data.id === data.id) this.selected = { kind, data };
      } catch (e) {
        this.error = 'The details could not be loaded.';
      } finally {
        this.detailLoading = false;
      }
    },

    clearSelection() {
      highlight = null;
      this.selected = null;
      renderer?.refresh({ skipIndexation: true });
    },

    async rebuild() {
      this.rebuildState = 'sending';
      try {
        const response = await fetch(`/api/v1/crates/${crateId}/graph/rebuild`, { method: 'POST', headers: { Accept: 'application/json', ...csrf() } });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        this.status = await response.json();
        this.rebuildState = 'scheduled';
      } catch (e) {
        this.rebuildState = 'failed';
      }
    }
  };
}
