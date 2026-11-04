/** @type {import('@docusaurus/plugin-content-docs').SidebarsConfig} */
const sidebars = {
  docs: [
    'index',
    {
      type: 'category', label: 'Architecture', collapsed: true,
      link: { type: 'doc', id: 'architecture' },
      items: ['crates', 'pipeline', 'search-backends', 'features/crate-backends', 'authorization'],
    },
    {
      type: 'category', label: 'Installation & administration', collapsed: true,
      link: { type: 'doc', id: 'installation/README' },
      items: [
        {
          type: 'category', label: 'Install', collapsed: true,
          items: ['installation/docker', 'installation/compose', 'installation/kubernetes', 'installation/jar', 'deployment'],
        },
        {
          type: 'category', label: 'Configure and secure', collapsed: true,
          items: ['configuration', 'security', 'operations/oidc', 'operations/administration'],
        },
        {
          type: 'category', label: 'Operate', collapsed: true,
          items: ['operations', 'grafana/README', 'backup-migration', 'upgrade-from-harvex', 'troubleshooting'],
        },
      ],
    },
    {
      type: 'category', label: 'Usage', collapsed: true,
      link: { type: 'doc', id: 'usage' },
      items: [
        'tutorials',
        {
          type: 'category', label: 'Features', collapsed: true,
          items: [
            {
              type: 'category', label: 'Chunking & indexing', collapsed: true,
              link: { type: 'doc', id: 'features/chunking' },
              items: ['features/parsing', 'features/semantic-chunking', 'features/contextual-headers', 'features/document-enrichment', 'features/images', 'embeddings'],
            },
            {
              type: 'category', label: 'Retrieval', collapsed: true,
              link: { type: 'doc', id: 'features/retrieval' },
              items: ['features/query-transformation', 'features/hyde', 'reranking'],
            },
            {
              type: 'category', label: 'Answer generation', collapsed: true,
              link: { type: 'doc', id: 'answers' },
              items: ['features/system-prompts', 'features/context-window', 'features/contextual-compression', 'features/grading', 'features/answer-verification'],
            },
            'features/evaluation',
          ],
        },
        {
          type: 'category', label: 'Integrations', collapsed: true,
          items: ['integrations/mcp', 'integrations/openai', 'api'],
        },
      ],
    },
    'roadmap',
  ],
};
export default sidebars;
