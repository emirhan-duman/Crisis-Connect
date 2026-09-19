# Data Inventory, Retention, and Deletion Standard

**Status:** Pilot security baseline  
**Owner:** Crisis Connect maintainers and the designated data controller  
**Review cadence:** Before each pilot, after a material data-flow change, and at least annually  
**Last reviewed:** 2026-09-19

## 1. Purpose and scope

This document records what Crisis Connect stores, why it is needed, where it is stored, who can
access it, and how it is removed. It covers the Android and iOS applications, Firebase Authentication,
Cloud Firestore, Cloud Storage, Cloud Functions, Firebase telemetry, and the agency dashboard data
model represented by this repository.

The standard distinguishes three states so that an intended control is never mistaken for an active
one:

- **Enforced** means repository code or a version-controlled rule performs the lifecycle action.
- **Deployment-dependent** means the application writes the required metadata, but an operator must
  verify the corresponding Firebase policy or scheduled service in the target project.
- **Decision required** means the repository does not yet impose a defensible retention period. The
  data controller must approve a period and engineering must automate it before production use.

`MUST`, `SHOULD`, and `MAY` describe operational requirements. Legal, regulatory, contractual, and
public-record obligations remain the responsibility of the designated data controller. A legal hold
may suspend deletion only for the affected records, with written scope, owner, start date, and review
date.

## 2. Data classification

| Class | Meaning | Examples | Minimum handling |
|:--|:--|:--|:--|
| **Restricted** | Disclosure could endanger a person, incident, or cryptographic identity | Precise location, SOS and medical data, message plaintext, private keys, tokens | Least privilege, encryption in transit and at rest, no production logging, explicit deletion path |
| **Confidential** | Identifies a user or exposes operational activity | Profile, phone hash, push token, responder role, device and audit metadata | Authenticated access, purpose limitation, bounded retention |
| **Internal** | Operational data with limited personal impact | Quota counters, deployment health, non-identifying deletion totals | Authorized operational access, documented lifecycle |
| **Public** | Intended for unrestricted disclosure | Published keys and manifests, project documentation | Integrity and provenance controls |

Encryption reduces exposure but does not remove data from this inventory. Ciphertext, hashed phone
identifiers, pseudonymous UIDs, IP addresses, and device tokens remain sensitive when they can be
linked, replayed, or used to contact a person.

## 3. Authoritative data inventory

| Data set | Class and purpose | System of record / access boundary | Lifecycle implemented in this repository | Account deletion behavior |
|:--|:--|:--|:--|:--|
| Authentication identity | Restricted; establish an account and session | Firebase Authentication; Admin SDK and the authenticated user | Retained while the account exists | Server callable deletes the Auth user last, after a recent-authentication check |
| User profile and preferences | Confidential; display identity and product settings | `users/{uid}` and local preferences; owner and authorized service paths | No age-based expiry | User tree is recursively deleted; sensitive local preferences are cleared |
| Contact discovery identifiers | Restricted; opt-in discovery by normalized identifier | `messagingDirectory`, `messagingKeys`; server-only writes and scoped lookups | Retained until replacement, opt-out, or account deletion | Directory hashes and identity-key tree are deleted |
| Signal prekeys, device routing, and presence | Restricted; encrypted session setup, delivery, and online state | `signalPreKeys`, `messagingTokens`, `presence`, `presenceSettings`; server-only or owner-scoped | Replaced as clients rotate state; no repository-wide age sweep | Trees, push tokens, presence, and settings are deleted |
| Store-and-forward message envelopes | Restricted ciphertext; temporary internet delivery | Root `messages`; sender/recipient and Functions only | **Enforced:** recipient acknowledgement deletes the envelope. `expireAt` is purged every 24 hours; legacy envelopes older than 30 days are swept. Clients normally request 24 hours; backend maximum is 30 days. | Queued envelopes sent by or addressed to the UID are deleted |
| On-device conversations, attachments, and keys | Restricted; offline and delivered conversation history | SQLCipher/SwiftData, app files, Android Keystore, iOS Keychain; app sandbox | User-controlled local history; no cloud conversation backup | In-app erase clears the enumerated personal databases/files and selected identity state. Android deliberately retains SQLCipher/AES keys, both platforms retain their attested device key, and other platform-managed key lifecycles require verification. |
| Cloud attachment objects and avatars | Restricted; profile and encrypted message media | Cloud Storage under `users/{uid}/` and `messageAttachments/{uid}/` | No repository-controlled age policy; Storage Rules are not versioned in this repository | Both UID prefixes are deleted by the server callable |
| SOS signal and routing records | Restricted; locate, triage, and coordinate aid | Panel `signals`, root routing records, related events/reporters; authorized rescue roles | **Decision required:** incident retention is not bounded in repository code | Direct identity is redacted rather than destroying an active rescue record; the user's reporter entries are deleted |
| Live rescue breadcrumb points | Restricted precise location; current field-team movement | Panel responder `track` subcollections | **Deployment-dependent:** clients write `expiresAt` at seven days. The target Firestore project must enable TTL for every matching collection group. Firebase deletion is asynchronous. | Parent rescue records may remain; deletion coverage must be proven with a collection-group test |
| Agency membership, roles, teams, devices, and certificates | Restricted/Confidential; responder authorization and accountability | Panel membership/role collections, `rescueDevices`, `certificates`; role-scoped access | Certificates expire cryptographically; expired-record deletion and other membership retention are not comprehensively automated | Root certificates and rescue-device records matching the UID are deleted; panel-wide UID coverage requires verification |
| Operational incident data | Restricted; signals, maps, annotations, resources, requests, inventory, agenda, workflows, calls, and coordination | Agency panel collections; agency/role-scoped access | **Decision required:** repository has no single closure state or approved lifecycle across these records | Only explicitly enumerated user-linked paths are erased or redacted; incident records may remain under the controller's approved purpose |
| Agency direct/channel messages and receipts | Restricted; responder coordination | Panel direct messages, secure messages, read receipts, typing/call signalling, and attachments | Short-lived signalling carries expiry fields in several paths; persistent message history has no unified repository-enforced retention | Deletion coverage is incomplete until all UID relationships and Storage prefixes are enumerated and tested |
| Crisis Sentinel local chats | Restricted; on-device assistance | Device app storage; app sandbox | User-controlled local history | Removed by the local erase flow |
| Crisis Sentinel cloud chats | Restricted plaintext; optional online assistance and dashboard sync | Panel `chats/{chatId}/messages`; user and authorized service/dashboard paths | **Decision required:** no age-based expiry | Chats owned by the UID are recursively deleted |
| Audit and security events | Confidential; investigate abuse and demonstrate administrative accountability | Root and panel audit collections, provider logs | **Decision required:** repository code does not define one retention period; payload minimization is required | Records needed for integrity may be retained under an approved schedule; they must not preserve unnecessary message, medical, or location content |
| Abuse controls and transient challenges | Confidential/Internal; rate limiting, OTP protection, attestation, certificate issuance | Throttle/stat, nonce, claim, and status collections; service-only | Several objects contain expiry fields or bounded validity, but physical deletion is not uniformly evidenced | Known per-user throttle trees are deleted; aggregate and service records require documented anonymization/expiry |
| Product telemetry and diagnostics | Confidential; optional reliability and product measurement | Firebase Analytics, Crashlytics, and Android Performance Monitoring | **Enforced client gate:** collection starts disabled and requires explicit opt-in. Vendor-side retention remains a deployment setting and must be recorded by operators. | Opt-out resets Analytics data and disables/deletes queued crash reporting as supported by each SDK; account deletion must be reconciled with vendor controls |
| Account-deletion tombstone | Internal; prove and remediate erasure | `accountDeletions/{uid}`; server-only | Contains deletion time, counts, source, and failed step names, but currently has no expiry | Created during deletion; must never contain contact, content, location, or profile fields |
| Backups and provider logs | Restricted/Confidential; disaster recovery and service security | Firebase/Google Cloud control plane | **Deployment-dependent:** no backup schedule or provider-log retention is versioned here | Live deletion does not by itself erase an existing backup. Backup expiry and restore-time erasure replay are mandatory controls. |

## 4. Active and target retention schedule

### 4.1 Repository-enforced controls

| Record | Trigger | Maximum live retention represented by code | Mechanism and evidence |
|:--|:--|:--|:--|
| Delivered message envelope | Recipient decrypts and stores it | Until acknowledgement completes | `acknowledgeMessage` deletes the server copy |
| Unacknowledged message envelope | `expireAt` passes | Client default 24 hours; backend accepts no more than 30 days; cleanup runs every 24 hours | `relayMessage`, envelope validation, and `purgeExpiredMessages` |
| Legacy envelope without `expireAt` | `createdAtMs` becomes older than 30 days | 30 days plus scheduled-job execution time | Daily legacy sweep in `purgeExpiredMessages` |
| Call and typing signals carried as message envelopes | Signal-specific TTL passes | Android uses 60 seconds for calls and 15 seconds for typing | Same acknowledgement and message purge path |
| SFU room/signalling state | Client-supplied `expireAt` passes | Room clients write 12 hours; consumers reject stale or excessive signal lifetimes | Physical deletion still requires verified Firestore TTL configuration |
| Rescue breadcrumb | Client-supplied `expiresAt` passes | Seven days plus Firebase TTL processing time | Physical deletion requires verified Firestore TTL configuration |

Firebase documents state that TTL deletion is typically completed within 24 hours after expiration,
expired documents can remain query-visible before deletion, and deleting a document does not delete
its subcollections. TTL therefore limits storage; application authorization and stale-data filters
must independently prevent expired records from being treated as current.

### 4.2 Pilot decisions and engineering gates

The following periods MUST be approved by the data controller and implemented before a production
pilot processes real participant data:

| Data family | Required decision | Implementation exit criterion |
|:--|:--|:--|
| SOS and incident records | Define closure, operational follow-up, statutory retention, anonymization, and legal-hold rules | One documented state machine; scheduled deletion/anonymization; sampled tests covering subcollections |
| Agency messages, media, calls, resources, and workflow history | Define retention from creation or incident closure for each purpose | TTL or scheduled purge on every applicable path, including Storage; dashboard filters must not expose expired data |
| Cloud AI chats | Approve a short, explicit inactivity period and user-controlled deletion behavior | Server-stamped expiry, recursive message deletion, dashboard control, and emulator tests |
| Audit/security logs | Set the minimum period needed for incident response and accountability, with a maximum | Schema allowlist, restricted access, automated expiry/archive, and proof that sensitive payloads are excluded |
| Certificates, devices, nonces, claims, throttles, and deletion tombstones | Define operationally necessary post-expiry windows | Expiry fields plus collection-group cleanup/TTL and monitoring |
| Firebase telemetry and provider logs | Record the selected vendor retention and regional settings | Dated configuration evidence, consent mapping, deletion process, and quarterly review |
| Backups | Select backup frequency, retention, access principals, region, and restore testing | Configuration export/evidence, expiry monitoring, restore runbook, and erasure replay test |

No table entry authorizes indefinite retention. If the controller cannot justify a period, the data
must not be collected for the pilot.

## 5. Account and local-data deletion

### 5.1 Server flow

`deleteAccountAndData` is the authoritative cloud erase path. It requires App Check, an authenticated
caller, and recent authentication. It performs idempotent, best-effort steps in this order:

1. Remove discovery hashes, identity material, prekeys, push routing, presence, and per-user throttles.
2. Remove queued message envelopes, role certificates, and rescue-device records.
3. Recursively remove the user's cloud AI chats.
4. Redact the identity link on SOS and routing records and delete the user's reporter records.
5. Recursively delete the profile tree and the known Cloud Storage prefixes.
6. Write a minimal server-only tombstone containing counts and failed step names.
7. Delete the Firebase Authentication user.

The client MUST present partial failure honestly and the operator MUST monitor tombstones whose
`failedSteps` is non-empty. The current implementation can delete Auth after an earlier best-effort
step failed, which prevents the former user from authenticating to retry. Automated privileged
remediation for those tombstones is therefore a **pilot blocker**.

The cascade also needs a maintained registry of every collection, field, subcollection, and Storage
prefix that can identify a UID. Schema changes that add a user relationship MUST update the erase
path and its tests in the same pull request.

### 5.2 Device flow

Android clears the local encrypted message database, stored role certificate, cached profile/contact
state, and selected identity preferences, then rotates the rescue-device identifier. It deliberately
retains SQLCipher/AES keys so the cleared database remains openable, and `clearStoredCertificate`
does not delete the attested signing key. iOS removes enumerated personal app-support directories and
sensitive preferences, clears the SwiftData model through its managed context, and deletes stored
certificate/public-key metadata; its attested device key and other non-enumerated Keychain items are
not deleted by this flow. Offline map/model assets may be preserved when they do not identify the
former user.

These retained keys MUST be classified by purpose and account linkage before the pilot. The deletion
design must either rotate/delete account-bound material or demonstrate with tests that retained
device-bound material cannot expose prior data, identify the former account, or grant its privileges.
Uninstall behavior for Keystore, Keychain, backup, and restore is platform-dependent and must not be
used as an erasure guarantee without release-specific evidence.

Release validation MUST cover sign-out, in-app account deletion, application reinstall, device
backup/restore, and interrupted deletion. Tests must prove that a new user cannot inherit the prior
user's messages, keys, profile, contacts, location history, role, or cloud session.

## 6. Backups, exports, and restoration

Firestore managed backups are separate from the live database. Google documents that deleting the
source database does not delete its backups, backup retention can be configured up to 14 weeks, and
TTL policies are not stored in a backup. Operators MUST therefore:

- document every scheduled backup/export, region, retention period, service account, and restore owner;
- keep backup access separate from routine application and dashboard administration;
- allow expired backups to age out under the approved schedule rather than silently retaining them;
- preserve a deletion journal sufficient to reapply erasures and redactions after a restore;
- re-enable and verify TTL policies after restoration;
- block the restored database from serving users until deletion replay and authorization checks pass;
- record and test the recovery point objective, recovery time objective, and restoration procedure.

Ad hoc exports and analyst downloads inherit the source classification and deletion deadline. They
MUST have an owner, purpose, access boundary, creation date, expiry date, and verified destruction.

## 7. Logging and observability rules

- Application and Function logs MUST NOT contain message or AI-chat content, phone numbers, precise
  locations, raw tokens, private keys, authorization headers, medical details, or attachment bodies.
- Stable UIDs SHOULD be replaced with short-lived correlation identifiers where investigation does
  not require identity. Error messages exposed to clients must not reveal other users or internal data.
- Audit events MUST identify the actor, action, target class, authorization result, server time, and
  correlation ID. They should record identifiers only when required for accountability.
- Cleanup jobs, TTL configuration, account deletion, and backup expiry MUST emit aggregate success,
  failure, oldest-record-age, and retry metrics without copying sensitive payloads.
- Alerts MUST fire when scheduled cleanup stops, the oldest eligible record exceeds its service-level
  objective, a deletion tombstone contains failures, or an unexpected collection lacks a lifecycle.

## 8. Deployment checklist

The release owner records dated evidence for each environment:

- [ ] Firestore Rules and Cloud Functions deploy from the reviewed commit.
- [ ] Firestore TTL is enabled on every intended collection group and exact field (`expireAt` or
      `expiresAt`); the exported policy list matches this inventory.
- [ ] A probe record expires and is physically removed within the monitored TTL window.
- [ ] Scheduled message purge is enabled, healthy, and alerts on failure and excessive record age.
- [ ] Cloud Storage Rules are version-controlled, tested, and deployed for every bucket and prefix.
- [ ] Account-deletion tests cover all user-linked collections, subcollections, and Storage objects,
      including deliberate partial failures and privileged remediation.
- [ ] Backup schedule, retention, region, access list, restore runbook, and deletion replay are approved.
- [ ] Firebase telemetry remains disabled by default; selected vendor retention and consent behavior
      are evidenced for Android and iOS.
- [ ] Dashboard roles use least privilege and export/download paths have expiry and audit controls.
- [ ] The controller has approved all entries marked **Decision required**; unresolved entries block
      real participant data.

## 9. Verification and change control

At least quarterly during a pilot, and before every material schema release, operators MUST run a
sampled lifecycle exercise using non-production identities:

1. Seed every user-linked and incident-linked data path, including nested subcollections and objects.
2. Exercise message acknowledgement, expiry, account deletion, incident closure, and legal hold.
3. Query by UID, hash, token, device ID, chat ID, signal ID, and Storage prefix after each deadline.
4. Verify the application and dashboard cannot read expired data while physical deletion is pending.
5. Inspect deletion failures, job health, oldest-record age, audit output, and backup expiry.
6. Restore an approved backup into an isolated project, replay erasures, reapply TTL, and prove that
   erased identities do not reappear before the project is released for use.

A pull request that introduces a collection, object prefix, log field, telemetry event, backup, export,
or third-party processor MUST update this inventory and state its purpose, classification, access,
retention trigger, deletion mechanism, and account-deletion behavior. Reviewers must reject ambiguous
or unbounded collection of Restricted data.

## 10. Open pilot blockers

| Priority | Gap | Required evidence to close |
|:--|:--|:--|
| **P0** | Failed account-deletion steps can coexist with Auth deletion, leaving no user-driven retry | Privileged retry worker/queue, alert, idempotent tests, and zero unresolved failure tombstones |
| **P0** | No complete, tested registry of UID relationships across all panel collections | Automated seeded deletion test and documented coverage for direct/secure messages, devices, receipts, calls, workflows, and nested records |
| **P0** | Cloud Storage Rules are absent from version control | Reviewed rules, emulator tests, deployment binding, and inventory of all buckets/prefixes |
| **P1** | Firestore TTL deployment is referenced by clients but not evidenced in this repository | Exported TTL policy list, deployment runbook, monitor, and probe-deletion result |
| **P1** | Cloud AI plaintext and operational dashboard records lack approved maximum retention | Controller decision plus automated recursive cleanup and tests |
| **P1** | Backup/provider-log configuration is not represented in repository evidence | Approved configuration record, access review, expiry monitor, restore and erasure-replay test |
| **P1** | Audit, certificate, nonce, throttle, and deletion-tombstone physical retention is incomplete | Approved periods, automated cleanup, and oldest-record-age alert |
| **P1** | Account deletion retains Android SQLCipher/AES keys and both platforms' attested device key | Written key classification plus rotation/deletion or tests proving no prior-data access, former-account linkage, or privilege reuse |

## 11. References

- [Crisis Connect threat model](THREAT_MODEL.md)
- [Secure development guide](SECURE_DEVELOPMENT.md)
- [Security incident response plan](INCIDENT_RESPONSE.md)
- [Firebase: Manage Cloud Firestore data with TTL policies](https://firebase.google.com/docs/firestore/ttl)
- [Firebase: Scheduled backups](https://firebase.google.com/docs/firestore/backups)
- [Firebase: Delete data](https://firebase.google.com/docs/firestore/enterprise/delete-data-native)
- [Firebase Authentication: Manage users](https://firebase.google.com/docs/auth/admin/manage-users)
- [Firebase: Disaster recovery planning](https://firebase.google.com/docs/firestore/disaster-recovery)
