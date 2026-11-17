# Answer feedback

Answer feedback lets people rate answers, lets those ratings improve later retrieval, and turns well rated
answers into new documents of the crate. It has three parts that are switched on separately, **per crate**
(default: all off) in the **Answer feedback** section of the crate's **Settings** page.

## 1. Collecting ratings

With **Enabled** on, every answer that is generated can be rated on two scales from 1 (bad) to 5 (very good):

- **Relevance:** did the retrieved information match the question?
- **Quality:** was the answer correct, complete and useful?

A person can also leave a comment describing what went wrong. Each rating is stored with the question, the answer
and the sources that were used (their document identity, so it still applies after a re-index or a new version).

**Where people rate**

| Channel | How |
|---|---|
| Chat page | A small row under the answer: two star rows and a *Comment* link. One click saves a rating. |
| OpenAI-compatible endpoint | A line at the end of the answer, after a horizontal rule: `Was this answer helpful? Rate it`. The link opens a page where both scales and the comment can be given. Streaming and non-streaming responses carry it. |
| REST | `POST /api/v1/crates/{crateId}/feedback` with `{"token", "relevance", "quality", "comment"}` (the token comes with the `complete` event of the answers stream as `feedbackToken`). |

**The rating link.** Every rateable answer gets a random token (256 bits). Only its SHA-256 hash is stored. The page
`/feedback/{token}` works **without signing in**, because OpenAI clients such as Open WebUI usually have no session
with ContextCrate. It shows only the question and an excerpt of the answer that the person already received.
The link expires after the configured time (default 7 days); unknown and expired links look the same. Unrated links
are deleted after they expire. A person can change a rating until the link expires.

Links use `contextcrate.public-url` when it is set (`CONTEXTCRATE_PUBLIC_URL`), otherwise the address of the request,
including the forwarded headers of a reverse proxy. Set it when ContextCrate is reached under another address than the
one your users open. Without any usable base URL no link is added to OpenAI responses.

Owners see the rated answers, approve or reject them for the index, and delete them in the **User feedback**
section of the crate's evaluation page (`GET /api/v1/crates/{crateId}/feedback` for the same list).

## 2. Feedback weighted retrieval

With **Weight retrieval by feedback** on, the candidate documents are re-weighted after the search:

1. The ratings of earlier answers that used one of the candidate documents are loaded (the newest ones, at most
   *Ratings per document* each, at most 40 in total).
2. For each rating the answer model is asked whether it is relevant for the **new** question (same topic, same needed
   information), using the earlier question and the comment. A failed or unclear reply counts as *not relevant*.
3. For every document with relevant ratings the score is multiplied by **average relevance rating ÷ 3**
   (1 → ×0.33, 3 → unchanged, 5 → ×1.67). Documents without relevant ratings keep their score.
4. The candidates are sorted again and the top K go to the answer model.

To give a favoured document a chance to move up, twice the number of sources is searched (at most 100) while the
feature is on. The weighting is applied per document, so it holds across re-chunking and re-indexing. It costs
one short model call per rating that is checked and takes effect immediately.

## 3. Enriching the index with rated answers

With **Add well rated answers to the index** on, a background job runs at the configured interval (default 24 hours).
Answers with at least the **minimum quality** (default 4) and **minimum relevance** (default 3) that used at least one
source become a document: the question and the verified answer, plus the titles of the sources. The document is
embedded and indexed like any other, so a later, similar question finds a proven question and answer.

- With **Owner approval required** (default) these answers wait as *candidates* until an owner approves them in the
  feedback list. Without it they are indexed at the next run. Approving is also a defence against someone who
  rates answers on purpose to put text into the index.
- The documents belong to an internal source named *User feedback*. They are not shown in the source list.
- Rejecting or deleting a feedback entry removes its document from the database and the index.

## What it does not do

- Ratings never change what the answer model is told; they only change which documents are chosen.
- Feedback is not part of crate exports.
- Rating needs no account, so anyone who holds a link can rate that one answer. The links are unguessable and expire.
