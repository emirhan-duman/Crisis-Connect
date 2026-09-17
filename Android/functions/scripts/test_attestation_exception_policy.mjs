import assert from "node:assert/strict";
import test from "node:test";

import {
  loadAttestationExceptionPolicy,
  parseUidAllowlist,
  resolveAttestationException,
} from "../lib/certificates/attestationExceptionPolicy.js";

test("missing configuration fails closed", () => {
  const policy = loadAttestationExceptionPolicy({});

  assert.equal(policy.testerUids.size, 0);
  assert.equal(policy.demoUids.size, 0);
  assert.equal(resolveAttestationException(policy, "any-uid"), "standard");
});

test("blank configuration fails closed", () => {
  const policy = loadAttestationExceptionPolicy({
    CC_TESTER_UIDS: "  , , ",
    CC_DEMO_UIDS: "\t, ",
  });

  assert.equal(resolveAttestationException(policy, "any-uid"), "standard");
});

test("allowlists trim, deduplicate, and ignore empty entries", () => {
  assert.deepEqual(
    [...parseUidAllowlist(" tester-a, ,tester-b, tester-a ")],
    ["tester-a", "tester-b"]
  );
});

test("explicit tester configuration relaxes only the listed UID", () => {
  const policy = loadAttestationExceptionPolicy({
    CC_TESTER_UIDS: "tester-a,tester-b",
  });

  assert.equal(resolveAttestationException(policy, "tester-a"), "tester");
  assert.equal(resolveAttestationException(policy, "tester-b"), "tester");
  assert.equal(resolveAttestationException(policy, "unlisted"), "standard");
});

test("explicit demo configuration applies only to the listed UID", () => {
  const policy = loadAttestationExceptionPolicy({
    CC_DEMO_UIDS: "demo-a",
  });

  assert.equal(resolveAttestationException(policy, "demo-a"), "demo");
  assert.equal(resolveAttestationException(policy, "unlisted"), "standard");
});

test("demo takes precedence when a UID appears in both explicit lists", () => {
  const policy = loadAttestationExceptionPolicy({
    CC_TESTER_UIDS: "shared",
    CC_DEMO_UIDS: "shared",
  });

  assert.equal(resolveAttestationException(policy, "shared"), "demo");
});
