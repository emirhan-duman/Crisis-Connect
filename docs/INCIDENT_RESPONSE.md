# Security Incident Response Plan

| Field | Value |
|:--|:--|
| System | Crisis Connect mobile clients, Firebase backend, dashboard data plane, and release pipeline |
| Audience | On-call maintainer, pilot operations lead, security responder, and authorized agency liaison |
| Plan owner | Repository maintainers; the pilot owner must assign named responders outside this public repository |
| Time standard | UTC for every incident record, log export, decision, and status update |
| Review cadence | Before each pilot phase, after every SEV-0/SEV-1 incident, and at least every six months |
| Related documents | [Threat model](THREAT_MODEL.md), [secure development](SECURE_DEVELOPMENT.md), [external interfaces](EXTERNAL_INTERFACES.md), [security policy](../SECURITY.md) |

This plan turns a suspected security or safety event into a controlled response. It follows the
preparation, detection, response, recovery, and improvement outcomes in
[NIST SP 800-61 Rev. 3](https://csrc.nist.gov/pubs/sp/800/61/r3/final). It is deliberately scoped to
the controls evidenced by this repository. The pilot owner must maintain the private contact tree,
Firebase and Google Cloud project identifiers, agency escalation contacts, legal/privacy contacts,
and vendor account identifiers in an access-controlled operational system.

## Non-negotiable response rules

1. **Protect people first.** Do not interrupt an active SOS, rescue, or verified emergency workflow
   solely to preserve evidence. Prefer the narrowest containment that stops abuse while keeping
   unaffected emergency paths available.
2. **Open one incident record immediately.** Give it an identifier in the form
   `CC-IR-YYYYMMDD-NNN`, record all times in UTC, and use the template at the end of this document.
3. **Use known-clean administration.** Respond from a managed workstation and a separately
   authenticated administrator account. Do not investigate from a suspected device or reuse a
   possibly exposed token.
4. **Preserve before changing when safe.** Export relevant logs and configuration metadata before
   containment. If active harm is occurring, contain first and record why evidence collection was
   deferred.
5. **Never paste secrets or sensitive payloads into tickets or chat.** Record secret resource names,
   versions, hashes, and access-log references. Store exports only in the approved evidence location.
6. **No silent recovery.** The incident commander records the containment decision, recovery gates,
   residual risk, and the person who authorized return to service.

## Readiness before pilot use

The pilot operations owner must complete these items and attach evidence to the pilot readiness
record:

- Assign a primary and backup incident commander, Firebase/GCP operator, security/evidence lead,
  communications lead, and AFAD or agency safety liaison. One person may cover several roles in a
  small team, but a second authorized person must review destructive production actions.
- Keep break-glass administrator accounts hardware-key protected, monitored, and unused for daily
  work. Verify recovery methods and remove stale owners from GitHub, Firebase, GCP, Apple, Google
  Play, Twilio, Cloudflare, and any model or map provider.
- Record the production `PROJECT_ID`, project number, regions, buckets, function inventory, app
  identifiers, provider account identifiers, and support contracts in the private runbook.
- Enable and test Cloud Audit Logs needed by the pilot, route security-relevant logs to a protected
  project or bucket, set retention, and restrict deletion. Google documents the available audit-log
  categories and their `protoPayload` structure in
  [Cloud Audit Logs](https://cloud.google.com/logging/docs/audit/understanding-audit-logs) and log
  routing through [Log Router sinks](https://cloud.google.com/logging/docs/routing/overview).
- Configure budget, quota, authentication, App Check, Function error-rate, OTP conversion, role
  certificate, and high-risk IAM alerts. Send alerts to at least two responders and test delivery.
- Verify that the Twilio usage trigger reaches `twilioSpendAlert` and that
  `system/otpConfig.enabled=false` blocks new OTP sends. Keep the re-enable operation manual.
- Test role-certificate revocation, Firebase refresh-token revocation, a Firestore Rules rollback,
  provider-secret rotation, release verification, and restoration from backup in a non-production
  project.
- Maintain an encrypted contact list and an out-of-band channel that remains available if GitHub,
  Firebase, or the normal team chat is compromised.
- Run a tabletop exercise before live responder data is introduced and record actions, gaps, owners,
  and due dates.

## Severity and response targets

Severity measures credible impact, not how dramatic an alert appears. If uncertain, start at the
higher level and downgrade with evidence.

| Level | Trigger examples | Acknowledge | Command established | Update cadence |
|:--|:--|:--|:--|:--|
| **SEV-0 Safety/Crisis** | Active SOS suppression or falsification; cross-agency command compromise; malicious emergency guidance; widespread responder lockout during an incident | 5 min | 10 min | 15 min |
| **SEV-1 Critical** | Confirmed privileged-account or signing-key compromise; material sensitive-data disclosure; production Rules bypass; malicious release; sustained high-cost abuse | 15 min | 30 min | 30 min |
| **SEV-2 High** | Contained account takeover; limited unauthorized access; exploitable issue without observed abuse; regional service degradation with safe fallback | 1 hour | 2 hours | 2 hours |
| **SEV-3 Moderate/Low** | Suspicious event with no confirmed access; policy violation; low-impact defect; unsuccessful attack requiring follow-up | 1 business day | As assigned | Daily or on change |

Any event affecting an active emergency, responder identity, routing decision, signing authority, or
multiple agencies is at least SEV-1. Elevate to SEV-0 when delay or incorrect data can credibly harm
people in the field.

## Roles and authority

| Role | Duties | May authorize |
|:--|:--|:--|
| Incident commander (IC) | Owns severity, objectives, decision log, staffing, update cadence, and closure | Containment and return to service within delegated pilot authority |
| Operations lead | Executes Firebase/GCP/provider actions and captures before/after evidence | Routine reversible actions; destructive actions require IC plus a second authorized reviewer |
| Security/evidence lead | Defines scope, preserves evidence, builds timeline, validates eradication | Evidence handling and technical validation |
| Safety/agency liaison | Assesses field impact and coordinates alternate operational procedures | Operational fallback with the relevant agency authority |
| Communications/privacy lead | Controls internal, agency, user, regulator, insurer, and public messages | Messages after IC and legal/privacy review where required |

The first qualified responder acts as temporary IC until a named IC accepts the role. Vendors and
automated tools provide evidence; they do not decide severity, disclosure, or return to service.

## First 30 minutes

### 1. Declare and stabilize

- Create the incident record, assign severity and IC, start the UTC timeline, and open the approved
  out-of-band coordination channel.
- Write the first objective as one sentence, for example: “Stop unauthorized certificate issuance
  while preserving existing SOS reporting.”
- Identify active emergency operations and ask the safety liaison which service paths must remain
  available. Announce a tested fallback before disabling a critical path.
- Freeze unrelated production deployments, role changes, secret rotation, release publication, and
  data cleanup. Record any emergency exception.

### 2. Establish facts

Record what raised the alert, first and last observed times, affected accounts/agencies/devices,
functions and collections involved, release versions, source IP or provider event references, and
whether the behavior is still active. Separate confirmed facts from hypotheses.

Use explicit project selection for every command. The
[Firebase CLI reference](https://firebase.google.com/docs/cli) supports `--project`; never rely on
an operator's remembered default during an incident.

```bash
export PROJECT_ID="approved-production-project-id"
firebase projects:list
firebase functions:log --project "$PROJECT_ID" --only reportSosSignal
firebase functions:log --project "$PROJECT_ID" --only issueRoleCertificate
```

Capture the command, authenticated operator, start/end UTC time, output location, and SHA-256 of
each export. `firebase functions:log --only <FUNCTION_NAME>` is the supported function-specific
view described in [Firebase logging guidance](https://firebase.google.com/docs/functions/writing-and-viewing-logs).
For broader investigation, use Logs Explorer and query Cloud Audit Logs by project, principal,
service, method, resource, and the narrow incident time window.

### 3. Choose containment

Contain the smallest trustworthy unit: one user, device, certificate, provider credential,
function, release, agency scope, or write path. Before executing, record the expected safety impact,
rollback, success signal, approver, and operator. Reassess after each action instead of stacking
changes blindly.

## Evidence handling

Create an incident evidence directory in the approved encrypted store; do not commit it to this
repository. Use append-only or retention-locked storage when available.

```text
CC-IR-YYYYMMDD-NNN/
  00-intake/
  10-cloud-logs/
  20-config-snapshots/
  30-provider-records/
  40-artifacts/
  90-hashes-and-chain-of-custody/
```

For every item record: evidence ID, description, source system and resource, collection method,
collector, acquisition time in UTC, original time zone if different, filename, SHA-256, storage
location, and every transfer or access. Preserve original files read-only and analyze copies.

Collect only data relevant to scope. Prefer metadata, document IDs, hashes, access decisions, and
provider event IDs over message bodies, exact coordinates, phone numbers, or authentication tokens.
If sensitive content is necessary, document the purpose, authorization, access list, retention, and
secure deletion date. Never place raw ID tokens, App Check tokens, private keys, OTP codes, APNs
keys, TURN credentials, or signed model URLs in the incident record.

Minimum evidence by surface:

| Surface | Preserve |
|:--|:--|
| Identity/IAM | Principal, role/claim change, token revocation time, login/admin audit events, affected UID hashes |
| Functions/App Check | Function revision/config metadata, error and request trends, enforcement state, verified/unverified metrics |
| Firestore/Storage | Rules release/version, IAM changes, relevant document metadata and audit events; content only when required |
| Certificates/SOS | `auditLogs`, certificate state, routing document/history, agency panel event identifiers, server timestamps |
| Providers | Twilio/Cloudflare/APNs/FCM event IDs, spend/quota events, credential version and rotation time |
| Release | Tag/commit, asset SHA-256, Sigstore bundle, SBOM attestation, workflow run, store release status |

## Containment playbooks

### Compromised account, device, or responder role

1. Identify the Firebase UID, agency scope, device ID, role claims, certificates, push tokens, and
   recent privileged activity. Avoid using names or phone numbers in the shared record.
2. Disable the Firebase user when continued access is unsafe, then revoke refresh tokens. Firebase
   states that ID tokens are short-lived while refresh tokens remain usable until revoked or a major
   account change occurs; use the documented
   [session revocation procedure](https://firebase.google.com/docs/auth/admin/manage-sessions).
3. Revoke the responder certificate through the authorized `revokeRoleCertificate` path and verify
   the `certificates` state plus the server-written `auditLogs` event. Remove or correct privileged
   custom claims and the corresponding authoritative profile state.
4. Invalidate affected messaging/push device registrations and require a clean re-enrollment.
5. Check for lateral impact: certificate issuance/listing, agency roster access, SOS routing,
   hierarchy channels, key publication, exports, and dashboard actions.
6. Restore access only after identity proofing, credential reset, clean-device validation, claim and
   certificate review, and explicit IC approval.

Refresh-token revocation can leave an already-issued ID token valid until it expires unless the
backend checks revocation. Treat revocation time as a boundary and investigate activity until the
last accepted token can no longer be used.

### Role-certificate or master signing-key compromise

1. Stop new certificate issuance using the narrowest reversible control available and publish an
   operational fallback for responders.
2. Preserve Secret Manager access logs, function revision/config metadata, issuance and revocation
   audit records, and affected certificate identifiers.
3. For a single-device event, revoke that certificate. For suspected `MASTER_PRIVATE_KEY_PEM`
   compromise, treat every certificate under that trust root as suspect; define a new trust epoch,
   rotate the secret, deploy reviewed verifier changes where required, and reissue certificates.
4. Test online and offline rejection of old or revoked certificates on both Android and iOS. Record
   the last revocation-list refresh visible to field devices.
5. Do not delete old audit records or key-version metadata needed to verify the timeline.

### Firestore Rules, dashboard, or cross-agency access failure

1. Preserve the active Rules version, deployment audit events, affected queries, actor/UID hashes,
   and representative denied/allowed request metadata.
2. Remove the compromised account or role first when that contains the event. If the policy itself
   is unsafe, prepare a minimal deny rule for only the affected collection or agency scope.
3. Add a failing emulator test that reproduces the unauthorized operation, then apply the smallest
   Rules change and run the complete Rules suite.
4. Deploy from a reviewed commit with an explicit project:

   ```bash
   cd Android
   firebase deploy --only firestore --project "$PROJECT_ID"
   ```

   Firebase warns that CLI deployment overwrites console-managed Rules, so compare the intended
   repository file with the active policy before deployment; see
   [Rules deployment guidance](https://firebase.google.com/docs/rules/manage-deploy).
5. Verify both denial of the attack and continued access for victim, responder, admin, and
   cross-agency negative cases. Reconcile console changes back into version control.

### OTP pumping or authentication-cost abuse

1. Set server-only `system/otpConfig.enabled` to `false`, add a short `pausedReason`, and record the
   server timestamp. The Function caches configuration for up to 60 seconds, so verify after that
   window and inspect new Twilio sends.
2. Preserve the signed Twilio usage-trigger request, provider event IDs, country/calling-code
   aggregates, spend alerts, and hashed throttle identifiers. Do not export raw phone numbers into
   the incident record.
3. Rotate `TWILIO_AUTH_TOKEN` if webhook or API credentials may be exposed; update the Function
   secret binding and verify signature validation before resuming.
4. Tighten country, global, attested/unattested, UID, IP, and phone ceilings based on confirmed data.
5. Re-enable manually only after spend is bounded, test numbers pass, alerts are armed, and the IC
   records a maximum acceptable exposure.

### App abuse, automation, or stolen client credentials

1. Identify affected API products, app versions, attestation providers, token-reuse metrics, and
   whether valid users can still attest.
2. Apply endpoint rate/cost controls first. Enable or tighten App Check only after checking valid
   traffic impact; Firebase notes that enforcement rejects unverified traffic and can take up to
   15 minutes to apply. Follow the official
   [App Check enforcement procedure](https://firebase.google.com/docs/app-check/enable-enforcement).
3. Revoke exposed API/provider credentials, remove unauthorized app registrations/debug tokens,
   and require a fixed client release if the authentic client itself is abusable.
4. Verify Android, iOS, and approved dashboard clients separately. Do not assume mobile enforcement
   proves the dashboard path is protected.

### SOS integrity, routing, or agency-panel incident

1. Escalate to the safety liaison immediately and establish a voice or other approved fallback for
   affected agencies.
2. Preserve `sosSignalRouting/{signalId}`, its `history` subcollection, the corresponding
   `agencyPanels/*/signals/{signalId}` records and event identifiers, relevant `auditLogs`, server
   timestamps, and dashboard access events.
3. Quarantine the responsible account, device, routing configuration, or agency scope. Avoid bulk
   deletion or status changes that could hide a real unresolved signal.
4. Label uncertain signals for human verification. Do not automatically resolve, reroute, or merge
   incidents based solely on compromised data.
5. Reconcile every affected signal with the agency, document the authoritative outcome, and test
   routing provenance plus cross-agency isolation before normal automation resumes.

### Messaging, push, TURN, or provider-secret compromise

1. Determine whether the event exposes plaintext, encrypted payloads, metadata, call availability,
   or only provider cost. Crisis Connect message confidentiality still depends on endpoint and key
   safety even when relay content is ciphertext.
2. Preserve Function logs and provider events without logging message bodies, push payloads,
   bearer credentials, SDP, or exact location.
3. Rotate only affected secrets: `APNS_AUTH_KEY_P8`, `APNS_KEY_ID`, `APNS_TEAM_ID`,
   `CLOUDFLARE_TURN_KEY_ID`, or `CLOUDFLARE_TURN_API_TOKEN`. Revoke the old provider credential and
   verify the new secret version is bound to the expected Function revision.
4. If a relay path must be disabled, communicate offline/BLE fallback and verify it on field devices
   before the change when conditions allow.
5. Test send, receive, acknowledgement, push, call setup, expiry, and unauthorized access before
   restoring the path.

### Malicious or unverifiable mobile release

1. Stop distribution, suspend staged rollout where the store permits it, and tell pilot operators
   not to install or sideload the affected asset.
2. Preserve the tag, commit, workflow run, runner identity, asset and SBOM hashes, Sigstore bundles,
   artifact attestation, store metadata, signing events, and downloaded suspect binary.
3. Compare the asset with [release verification](RELEASE_VERIFICATION.md). Treat any changed,
   missing, or invalid evidence as a release-blocking event.
4. Rotate signing or publishing credentials only when exposure is plausible; preserve version and
   access history first. Review GitHub Actions, environments, repository rules, maintainers, and
   third-party actions.
5. Build a higher version from a known-good commit through the reviewed workflow. Do not overwrite
   or silently replace the compromised asset. Publish an explicit security notice and upgrade path.

## Eradication and recovery gates

The IC may authorize recovery only when all applicable gates have evidence:

- the entry path and affected scope are understood well enough to prevent immediate recurrence;
- compromised accounts, sessions, roles, certificates, secrets, tokens, releases, and provider
  credentials are disabled, revoked, rotated, or otherwise bounded;
- the fix has a regression test or an independently repeatable validation;
- Firestore Rules, Functions, Android, iOS, dashboard, provider, and offline behavior affected by
  the change have been checked;
- logs and alerts show no continuing abuse for an incident-specific observation period;
- data and routing integrity have been reconciled with the agency when SOS or responder operations
  were involved;
- backups or exports used for recovery were scanned and validated before restoration;
- the rollback plan, owner, monitoring window, and abort threshold are recorded; and
- the safety liaison and IC accept the remaining operational risk.

Restore in stages. Start with internal test identities, then a small pilot cohort, then the remaining
population. Keep heightened monitoring and an immediate rollback path throughout the observation
window.

## Communications and disclosure

- Maintain one approved facts document. Mark statements as confirmed, suspected, or ruled out and
  include the “as of” UTC time.
- Tell affected agencies what capability is impaired, the safe fallback, required operator action,
  and the next update time. Do not speculate about attribution.
- Coordinate user, researcher, vendor, insurer, regulator, and public communication through the IC
  and the designated legal/privacy authority. The public vulnerability channel remains the private
  GitHub Security Advisory path in [SECURITY.md](../SECURITY.md).
- Notify people whose data or safety may be affected using verified contact paths. Include concrete
  protective steps and avoid exposing another person's data.
- Record every external statement, approver, audience, channel, and time. Correct material errors
  promptly in the same channel.

This repository cannot determine contractual or legal notification deadlines. The pilot owner must
record applicable jurisdictions, agreements, data-controller roles, and notification contacts
before collecting live data.

## Closure and improvement

Closure requires a written timeline, impact statement, root and contributing causes, evidence
index, containment and recovery record, residual risk, and tracked corrective actions. Hold the
review within five business days for SEV-0/SEV-1 and within ten for SEV-2. Include operations,
security, mobile/backend owners, privacy/legal as applicable, and the affected agency.

The review must answer:

- Which control detected the incident, and which control should have detected it sooner?
- Which access, deployment, audit, backup, fallback, or communication assumption failed?
- Did containment preserve emergency availability and cross-agency isolation?
- What source, test, alert, IAM, runbook, training, or vendor change prevents recurrence?
- Who owns each action, by when, and what evidence closes it?

Update the threat model and this plan when a new attack path, trust boundary, critical dependency,
or operational constraint is learned. Exercise the changed procedure rather than closing an action
on documentation alone.

## Incident record template

```markdown
# CC-IR-YYYYMMDD-NNN — Short factual title

- Severity / status:
- Incident commander / backup:
- Operations, security/evidence, communications, safety liaison:
- Declared at / first observed / last observed (UTC):
- Detection source and alert/event IDs:
- Current objective:
- Next update (UTC):

## Safety and service impact
- Active emergency operations affected:
- Agencies, platforms, versions, regions, accounts/devices, and data classes affected:
- Confirmed impact / credible worst case / safe fallback:

## Scope and facts
- Confirmed:
- Suspected:
- Ruled out with evidence:
- Indicators and query references (no secrets or unnecessary personal data):

## Timeline
| UTC time | Actor | Observation, decision, or action | Evidence/change ID | Result |
|:--|:--|:--|:--|:--|

## Decisions and changes
| Decision/change | Safety impact | Approver | Operator | Rollback | Verification |
|:--|:--|:--|:--|:--|:--|

## Evidence index
| ID | Source and description | Collected by / UTC | SHA-256 | Protected location / custody |
|:--|:--|:--|:--|:--|

## Recovery gates
- [ ] Entry path bounded
- [ ] Credentials, sessions, roles, certificates, and secrets reconciled
- [ ] Fix independently validated
- [ ] Data/routing integrity reconciled
- [ ] Monitoring window clean
- [ ] Safety liaison and IC approval recorded

## Communications
| UTC time | Audience/channel | Approved message reference | Approver |
|:--|:--|:--|:--|

## Closure
- Root and contributing causes:
- Residual risk:
- Corrective actions, owners, due dates, and closure evidence:
- Disclosure/notification decisions:
- Review date and participants:
```
