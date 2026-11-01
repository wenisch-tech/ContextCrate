/** @type {import('@docusaurus/plugin-content-docs').SidebarsConfig} */
const sidebars = {
  docs: [
    'index',
    'crates',
    'authorization',
    'architecture',
    'configuration',
    'chunking',
    'embeddings',
    'reranking',
    'answers',
    {
      type: 'category', label: 'Features', collapsed: true,
      items: ['features/retrieval', 'features/query-transformation', 'features/grading', 'features/answer-verification'],
    },
    'pipeline',
    'security',
    {
      type: 'category', label: 'Installation', collapsed: true,
      link: { type: 'doc', id: 'installation/README' },
      items: ['installation/docker', 'installation/compose', 'installation/kubernetes', 'installation/jar'],
    },
    'deployment',
    'backup-migration',
    'upgrade-from-harvex',
    'api',
    {
      type: 'category', label: 'Integrations', collapsed: true,
      items: ['integrations/mcp', 'integrations/openai'],
    },
    {
      type: 'category', label: 'Operations', collapsed: true,
      link: { type: 'doc', id: 'operations' },
      items: ['operations/administration', 'operations/oidc'],
    },
    'tutorials',
    'roadmap',
    'troubleshooting',
  ],
};
export default sidebars;
