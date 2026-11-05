# Administration panel

Global administrators reach the installation-wide panel at `/admin`, linked from the navigation bar
and from a tile on the crate overview. Everything on this page is also available through
`/api/v1/admin/**` — see [API](../api.md).

Access is checked per request against `app_user.role == "ADMIN"`. Users without that role get a
`403`, and the entry points are hidden from their navigation.

## Users

The **Users** tab lists every account in the installation.

| Action | Effect |
| --- | --- |
| Create user | Creates a `USER` account with an administrator-issued temporary password. The account cannot create crates until entitled, and must change the password at the first sign-in. |
| Can create crates | Toggles `app_user.can_create_crates`. Only relevant when the crate creation mode is `ENTITLED_USERS`. |
| Make admin / Revoke admin | Sets `app_user.role` to `ADMIN` or `USER`. |
| Enable / Disable | Sets `app_user.enabled`. A disabled account cannot sign in. |
| Reset password | Sets a temporary password and forces a change at the next sign-in. |

### Lock-out protection

Two rules are enforced for every path — panel and API alike — and cannot be bypassed:

- You cannot disable or demote **your own** account.
- The **last enabled administrator** cannot be disabled or demoted.

The second rule exists because the start-up routine that guarantees an administrator
(`SecurityConfig.initializeAdmin`) fails when neither the configured
`contextcrate.security.initial-admin-email` nor any `ADMIN` account exists — the installation would
no longer boot.

:::warning OIDC overrides local roles
When `contextcrate.security.oidc.enabled` is `true`, the role is re-derived from the identity
provider on **every** sign-in. An administrator role granted here is reset to `USER` the next
time that user signs in through Keycloak unless they also hold the `ContextCrate_Admin` role
there. See [OIDC with Keycloak](oidc.md).
:::

## Policies

The **Policies** tab holds the single installation-wide runtime setting, `crate_creation_mode`:

| Mode | Who may create crates |
| --- | --- |
| `EVERYONE` | Every signed-in user. |
| `ADMINS_ONLY` | Administrators only. |
| `ENTITLED_USERS` | Administrators plus users with the crate-creation entitlement. |

Administrators may always create crates, regardless of the mode.

Every other runtime setting is **per crate** and lives on that crate's settings page
(`/crates/{crateId}/settings`) — retrieval policy, grading, answer verification, and model
providers.

### Onboarding policy

The onboarding policy is applied prospectively when a local account is created from the Users tab
or an OIDC account signs in for the first time. Changing it never changes existing users or crate
memberships, and it applies to administrator accounts too.

| Policy | Effect for a new account |
| --- | --- |
| `ADD_TO_EXISTING_CRATE` | Adds the account to the selected crate as a `VIEWER`. |
| `SHOW_NEW_CRATE_DIALOG` | Sends the account to `/crates` and requires creating one crate before continuing. This grants one creation even if the normal crate-creation policy would deny it. The user owns the crate they create. |
| `DO_NOTHING` | Leaves the account on `/crates` without adding a membership or granting creation rights. |

The selected crate must exist when `ADD_TO_EXISTING_CRATE` is saved. Automatic membership is
recorded in that crate's audit log as a system onboarding action.

## Crates

The **Crates** tab lists every crate in the installation with its status and content metrics, not
just the ones the administrator belongs to.

A global administrator has **no access to crate content by default**. To act inside a crate, start
an **elevation**: it grants owner-equivalent access to that one crate for 30 minutes, requires a
written reason, and writes an audit entry for the elevation itself and for every access made under
it. Active elevations are listed at the top of the tab and can be ended early; they also expire on
their own and are shown as a banner inside the elevated crate.

Archive, restore, and purge are deliberately **not** offered from this panel. They require the
`OWNER` role on the crate, so the correct order is: start an elevation, then perform the operation
inside the crate. See [Authorization](../authorization.md).

## Model policy

Under **Policies → Model policy**, three switches (all on by default) control what crate owners may
configure themselves: adding their own OpenAI-/Cohere-compatible endpoints, changing local
ONNX model settings (model id, revision, download URL, paths), and sending images to a vision
model to have them described (see [Images](../features/images.md)). Shared models are always selectable.
Turning a switch off does not break crates that already use such a setup; it only makes that part
of their provider settings read-only. Downloading the already-configured local model stays possible.

## System prompts

Under **Policies → System prompts**, an administrator can write an installation-wide system prompt for the answer model and allow
(off by default) or forbid crate owners to add their own. The built-in rules always come first, then the installation-wide prompt, then the crate's prompt; a later layer cannot
override an earlier one. See [System prompts](../features/system-prompts.md).

## Duplicating a crate

**Administration → Crates → Duplicate** copies a crate's sources, ingestion jobs, extraction rules and settings (including tokens, passwords and API keys, server-side only)
into a new, empty crate that you own, with a new name and backends chosen as in the create dialog. See [Crates](../crates.md#duplicating-a-crate).

## Deleting a crate

Global administrators can delete any crate under **Administration → Crates → Delete** without an elevation, because deleting exposes no content.
Crate owners do the same under **Settings → Delete crate**. In both places the crate name has to be typed to confirm. The crate, its documents,
files, search index entries (also in a dedicated backend), members and API keys are removed in the background. If a store cannot be reached the crate
shows `PURGE_FAILED` and **Retry deletion** starts it again.

## Dedicated storage and search policy

Under **Policies → Dedicated storage and search**, two switches (both **off** by default) decide whether crate creators may
create a crate with its own S3-compatible storage and/or its own search backend (OpenSearch, Elasticsearch, Qdrant, pgvector)
instead of the installation's providers. The choice is made in the **Create a crate** dialog, is tested before the crate is
created, and is fixed afterwards. Endpoints in private networks are refused unless `contextcrate.crawler.allow-private-networks`
is on. A third switch, **Users may ignore unsafe backends** (off by default), lets creators skip TLS certificate validation for their own backend, for example with a self-signed certificate. `contextcrate.tls.trust-all-certificates` covers these backends too. Turning a switch off does not affect existing crates. See
[Dedicated storage and search per crate](../features/crate-backends.md).

## Models

The **Models** tab manages **shared models**: named embedding, reranking, answer and judge endpoints that
crate owners can select instead of configuring their own. Each entry has a name, endpoint URL,
model identifier, optional API key, and an **Available to all users** switch.

- Crate owners see the shared models in the Embeddings, Reranking and Answer model sections of
  their crate settings, listed by name. The endpoint URL, model identifier and API key are never
  sent to them — not in the settings page, the settings API, crate exports, or the model name
  reported by the OpenAI-compatible endpoint (which shows the shared name).
- Unticking *Available to all users* hides the model from new selections; crates already using it
  keep working. A model that is still used by a crate cannot be deleted.
- Changing the endpoint, model identifier, key or vector size of an embedding model re-indexes
  every crate using it in the background (answer models do so for crates with proposition
  retrieval).
- **Make default for new crates** (only possible while the model is available to users) preselects
  and enables the model for every crate created afterwards. There is one default per type; setting
  a new one replaces the old. Existing crates are not changed.
- A model of type **Judge for RAG evaluation** works like the other shared models, but is used only by
  [RAG evaluation](../features/evaluation.md). Crate owners pick it by name in the *Judge model* section
  of their crate's Evaluation page (or enter their own endpoint, if the model policy allows custom
  endpoints). *Available to all users* controls whether it is offered, and *Make default for new
  crates* preselects it for new crates; evaluation itself stays off until an owner enables it.
- API keys are stored in the database like the per-crate ones; restrict database access accordingly.

## System

The **System** tab shows the deployment profile and node role, the configured backends (queue,
database, artifacts, index), the current queue depth per pipeline stage, and dead-lettered work
items. Dead letters can be requeued individually.
