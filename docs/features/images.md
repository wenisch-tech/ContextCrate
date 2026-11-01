# Images

Images in documentation carry information that text search cannot see: architecture diagrams,
screenshots, charts. ContextCrate can make the **meaningful** ones searchable and citable, without
indexing every icon, logo and badge a page contains.

Image handling is configured per crate under **Settings → Images**, and can be switched off
completely.

## Settings

| Setting | Default | Meaning |
|---|---|---|
| **Index images** | on | Master switch. Off: images are ignored everywhere, including image files in Git. |
| **Describe images with a vision model** | off | Downloads each image that passes the filter and has a vision model describe it. |
| Vision model | the crate's answer model | A shared answer model chosen by an administrator, or the crate's own answer model. It must accept images. |
| Maximum images described per run | 200 | Upper bound on vision calls (and cost) per ingestion run. |
| Maximum bytes per image | 5 MiB | Larger images are skipped. |
| Smallest image | 64 px | Images declared smaller than this in width or height are skipped. |
| Extra skip patterns | empty | Comma-separated. An image whose address, class or alt text contains one is skipped. |

The same fields are available through `PUT /api/v1/crates/{crateId}/settings/rag`:
`imageIndexingEnabled`, `imageDescriptionEnabled`, `imageVisionModelId`, `imageMaxPerRun`,
`imageMaxBytes`, `imageMinSize`, `imageSkipPatterns`. Omitted fields keep their value.

Changes apply to the next ingestion run. Switching image handling off does not remove image
entries that are already indexed; they disappear when the documents are ingested again.

An administrator can forbid vision descriptions for the whole installation under
**Policies → Model policy** (see [Administration](../operations/administration.md#model-policy)).
With that switch off, **Describe images** cannot be turned on.

## Two stages

### 1. Image references (no downloads)

For every image that passes the filter, the parser adds a line to the text, next to the
surrounding paragraph:

```text
[Image: Architecture of the ingestion pipeline — Figure 1] (https://docs.example.com/img/arch.png)
```

The label is the alt text, else the title, else the file name. A caption (`<figcaption>`) is
included. This line is chunked and indexed with the text around it, so a question about the
architecture finds the paragraph and the image reference together, and the answer can cite the
image address. Nothing is downloaded and no model is involved.

### 2. Descriptions (opt-in)

With **Describe images** on, every image that passed the filter is queued as an `IMAGE_DESCRIBE`
task:

1. The image is downloaded (web) or read from the checkout (Git). Downloads follow the same
   safety rules as crawling: private and metadata addresses are blocked, redirects are checked at
   every hop, the size is limited. Pages that need a login do not expose their images this way.
2. Post-download checks skip images under 5 KB, with a shorter side under 128 px, or banner-shaped
   (longer than eight times the shorter side). SVG files are not sent to a model; their own title
   and description are used.
3. A vision model gets the image and is asked to describe it, transcribe visible text and list the
   components of diagrams. If it answers `DECORATIVE`, the image is skipped.
4. The description is added to the document as an `image` chunk under the heading where the image
   appeared, with the image address, and the document is re-indexed.

Identical images (same content) in a crate are described once and the description is reused.
A failure only marks that image as failed; the run and the document are not affected.

## What is skipped

Images are filtered before anything is downloaded. Skipped images are listed with the reason on
the document's **Images** section.

| Rule | Examples |
|---|---|
| Marked decorative | `alt=""`, `role="presentation"`, `aria-hidden="true"` |
| Too small | declared width or height under the smallest-image setting |
| Name hints | `icon`, `logo`, `avatar`, `emoji`, `badge`, `sprite`, `spinner`, `favicon`, `social`, `share`, `button`, `arrow`, `pixel`, `tracking` as a word in the address, class, id or alt text (`company-logo.png` matches, `silicon.png` does not) |
| Badges and trackers | `img.shields.io`, `badgen.net`, gravatar, analytics and ad pixels |
| Other | `.ico` files, tiny inline `data:` images, SVG sprite references, duplicates on the page |
| Per-page limit | the first 20 images that pass |

A real caption, or an alt text of four or more words, outranks a name hint: a diagram called
`logo-architecture.png` with the alt text "Architecture of the ingestion pipeline" is kept.

Navigation, header and footer images are already gone when the job removes `nav, footer, aside`
(the default **Remove selectors**).

## Where images are found

| Source | What is read |
|---|---|
| Web pages | `img` (the largest `srcset` entry, or `data-src`), `picture`, `figure` with `figcaption`, an image wrapped in a link to a larger image, and inline `svg` with a `title` or `desc` |
| Markdown | `![alt](path "title")`. Relative paths are resolved against the file and turned into a link to the file on GitHub, GitLab or Forgejo. |
| Git image files | `.png`, `.jpg`, `.gif`, `.webp`, `.svg` that match the source's include and exclude globs. Each becomes a small document searchable by its file name and path; with descriptions on, also by its content. |

Images referenced from Markdown in a repository are not downloaded for descriptions, because the
checkout is gone after the run. Describe them by letting the image file itself be imported
(include `docs/img/**` for example).

## Privacy and security

- With **Describe images** on, image content is sent to the configured model endpoint. It is off
  by default, and an administrator can forbid it.
- A description is retrieved content like any other: it is treated as untrusted when it is given
  to the answer model, because an image can contain text that tries to give instructions.
- API keys of shared models are never shown to crate owners.

## Metrics

`contextcrate_images_total{result="kept|skipped"}` counts filter decisions and
`contextcrate_images_descriptions_total{outcome="described|skipped|failed"}` the vision results.
