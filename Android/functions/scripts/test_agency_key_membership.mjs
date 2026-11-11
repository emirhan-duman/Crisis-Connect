import assert from "node:assert/strict";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { resolveAgencyMessagingPanelId } = require("../lib/messaging/agencyMembership.js");

assert.equal(
  resolveAgencyMessagingPanelId({
    panelId: "canonical-panel",
    agencySlug: "stale-slug",
    agencyKey: "stale-key",
  }),
  "canonical-panel",
  "canonical panelId must override stale legacy aliases",
);
assert.equal(
  resolveAgencyMessagingPanelId({ agencySlug: " Legacy/Slug ", agencyKey: "stale-key" }),
  "legacy-slug",
  "agencySlug must remain the first legacy fallback",
);
assert.equal(
  resolveAgencyMessagingPanelId({ agencyKey: "AGENCY-KEY" }),
  "agency-key",
  "agencyKey-only production accounts must remain supported",
);
assert.equal(
  resolveAgencyMessagingPanelId({ agency: "Display Name Only" }),
  null,
  "display names must not become key-service membership authority",
);

console.log("agency messaging membership tests passed");
