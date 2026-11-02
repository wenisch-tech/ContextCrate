/** @type {import('@docusaurus/plugin-content-docs').SidebarsConfig} */
const sidebars = {
  docs: [
    'index',
    {
      type: 'category', label: 'Architecture', collapsed: true,
      link: { type: 'doc', id: 'architecture' },
      items: ['crates', 'pipeline', 'authorization'],
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
          items: ['operations', 'backup-migration', 'upgrade-from-harvex', 'troubleshooting'],
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
            'features/parsing', 'features/chunking', 'features/images', 'embeddings',
            'features/retrieval', 'reranking', 'features/query-transformation', 'features/hyde', 'answers',
            'features/grading', 'features/answer-verification',
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
