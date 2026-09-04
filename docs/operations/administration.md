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

!!! warning "OIDC overrides local roles"
    When `contextcrate.security.oidc.enabled` is `true`, the role is re-derived from the identity
    provider on **every** sign-in. An administrator role granted here is reset to `USER` the next
    time that user signs in through Keycloak unless they also hold the `ContextCrate_Admin` role
    there. See [OIDC with Keycloak](oidc.md).

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

## System

The **System** tab shows the deployment profile and node role, the configured backends (queue,
database, artifacts, index), the current queue depth per pipeline stage, and dead-lettered work
items. Dead letters can be requeued individually.
