// @ts-check
import { themes as prismThemes } from 'prism-react-renderer';

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'ContextCrate',
  tagline: 'Self-hosted crawling and indexing platform',
  favicon: 'img/favicon.png',
  // Served at https://contextcrate.eu/docs/ (FTP) and https://wenisch-tech.github.io/ContextCrate/
  // (GitHub Pages); the Pages build overrides these through the environment.
  url: process.env.DOCS_URL || 'https://contextcrate.eu',
  baseUrl: process.env.DOCS_BASE_URL || '/docs/',
  organizationName: 'wenisch-tech',
  projectName: 'ContextCrate',
  onBrokenLinks: 'throw',
  onBrokenAnchors: 'warn',
  trailingSlash: true,

  // The existing pages are plain CommonMark (they contain text such as <crateId>), so only
  // files that opt in via .mdx are parsed as MDX.
  markdown: {
    format: 'detect',
    mermaid: true,
    hooks: { onBrokenMarkdownLinks: 'throw' },
  },
  themes: [
    '@docusaurus/theme-mermaid',
    [
      '@easyops-cn/docusaurus-search-local',
      { hashed: true, docsDir: '../docs', indexBlog: false, docsRouteBasePath: '/', highlightSearchTermsOnTargetPage: true },
    ],
  ],

  presets: [
    [
      'classic',
      /** @type {import('@docusaurus/preset-classic').Options} */
      ({
        docs: {
          path: '../docs',
          routeBasePath: '/',
          sidebarPath: './sidebars.js',
          editUrl: 'https://github.com/wenisch-tech/ContextCrate/edit/main/docs/',
        },
        blog: false,
        theme: { customCss: './src/css/custom.css' },
      }),
    ],
  ],

  themeConfig: /** @type {import('@docusaurus/preset-classic').ThemeConfig} */ ({
    image: 'img/social-card.png',
    colorMode: { defaultMode: 'dark', respectPrefersColorScheme: true },
    navbar: {
      title: 'ContextCrate',
      logo: { alt: 'ContextCrate', src: 'img/logo.png' },
      items: [
        { type: 'docSidebar', sidebarId: 'docs', position: 'left', label: 'Documentation' },
        { href: 'https://contextcrate.eu', label: 'Website', position: 'right' },
        { href: 'https://github.com/wenisch-tech/ContextCrate', label: 'GitHub', position: 'right' },
      ],
    },
    footer: {
      style: 'dark',
      copyright: 'ContextCrate is open source, licensed under AGPL-3.0.',
      links: [
        {
          title: 'Project',
          items: [
            { label: 'GitHub', href: 'https://github.com/wenisch-tech/ContextCrate' },
            { label: 'Report a bug', href: 'https://github.com/wenisch-tech/ContextCrate/issues/new' },
          ],
        },
      ],
    },
    docs: { sidebar: { hideable: true, autoCollapseCategories: true } },
    tableOfContents: { minHeadingLevel: 2, maxHeadingLevel: 3 },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['bash', 'yaml', 'java', 'properties', 'json', 'docker'],
    },
    mermaid: { theme: { light: 'neutral', dark: 'dark' } },
  }),
};

export default config;
