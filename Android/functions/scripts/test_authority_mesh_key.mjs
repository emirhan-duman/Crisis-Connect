import assert from "node:assert/strict";
import { generateKeyPairSync, sign, createCipheriv, createDecipheriv, randomBytes } from "node:crypto";
import { createRequire } from "node:module";
import { test } from "node:test";
const require = createRequire(import.meta.url);
const { AUTHORITY_KEY_PERIOD_MS: period, AUTHORITY_KEY_PROOF_NONCE: nonce, authorityRolloutAllows } = require("../lib/certificates/authorityMeshKeyPolicy.js");
const moduleApi = require("node:module"), originalLoad = moduleApi._load;
const documents = new Map(); let role = "fieldteam";
class HttpsError extends Error { constructor(code, message) { super(message); this.code = code; } }
const database = { doc: path => ({ path, get: async () => ({ data: () => documents.get(path) }) }), runTransaction: async fn => fn({
    get: async ref => ({ exists: documents.has(ref.path), data: () => documents.get(ref.path) }),
    set: (ref, value) => documents.set(ref.path, value),
}) };
moduleApi._load = function(name, parent, main) {
    if (name === "firebase-functions/v2/https") return { HttpsError, onCall: (_options, handler) => handler };
    if (name === "firebase-admin/firestore") return { getFirestore: () => database, FieldValue: { serverTimestamp: () => "test-time" } };
    if (name === "./callerRole" && /\/(authorityMeshKey|authorityMeshKeyV2|securityProtocolCapabilities)\.js$/.test(parent?.filename ?? "")) return {
        requireUid: request => request.auth.uid, resolveCallerRoleKey: async () => role,
        CERT_ELIGIBLE_ROLE_KEYS: new Set(["admin", "fieldteam"]),
    };
    return originalLoad.apply(this, arguments);
};
const { issueAuthorityMeshKey } = require("../lib/certificates/authorityMeshKey.js");
const { issueAuthorityMeshKeyV2 } = require("../lib/certificates/authorityMeshKeyV2.js");
const { getMessagingSecurityCapabilitiesV2 } = require("../lib/certificates/securityProtocolCapabilities.js");
moduleApi._load = originalLoad;
const originalNow = Date.now; let now = 10 * period + 1000; Date.now = () => now;
const device = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
const publicKey = device.publicKey.export({ format: "der", type: "spki" }).toString("base64");
function provision(uid) {
    const certificate = { ownerUid: uid, deviceId: "cc-test-device", publicKeyBase64: publicKey,
        signatureBase64: "issuer-signature", issuedAtMs: 9 * period, expiresAtMs: 20 * period,
        status: "active", role: "fieldteam" };
    documents.set(`certificates/cert_${uid}`, certificate); return certificate;
}
function proof(uid, privateKey = device.privateKey, extra = {}) {
    const c = documents.get(`certificates/cert_${uid}`);
    const certificate = Buffer.from(JSON.stringify({ uid, did: c.deviceId, iat: c.issuedAtMs, exp: c.expiresAtMs, sig: c.signatureBase64 })).toString("base64");
    const payload = { devicePublicKey: publicKey, certificate, timestamp: now, sessionNonce: nonce, allowExpiredCertificate: false, ...extra };
    payload.signature = sign("sha256", Buffer.from(`${payload.devicePublicKey}|${payload.certificate}|${payload.timestamp}|${payload.sessionNonce}`), privateKey).toString("base64");
    return { auth: { uid }, data: payload };
}
test("requires a fresh active device proof and rotates independently every 72 hours", async () => {
    try {
        provision("alice"); provision("bob");
        const legacyKey = randomBytes(32).toString("base64");
        documents.set("authorityMeshKeys/current", { keyId: "authority-v1", keyBase64: legacyKey });
        await assert.rejects(issueAuthorityMeshKeyV2({ auth: { uid: "alice" }, data: {} }), { code: "permission-denied" });
        const legacy = await issueAuthorityMeshKey({ auth: { uid: "alice" }, data: {} });
        assert.deepEqual(legacy, { keyId: "authority-v1", keyBase64: legacyKey });
        await assert.rejects(issueAuthorityMeshKeyV2(proof("alice")), { code: "failed-precondition" });
        documents.set("securityProtocolRollouts/authority-v2", { enabled: true, allowedUids: ["bob"] });
        await assert.rejects(issueAuthorityMeshKeyV2(proof("alice")), { code: "failed-precondition" });
        documents.set("securityProtocolRollouts/authority-v2", { enabled: true, allowedUids: ["alice", "bob"] });
        const first = await issueAuthorityMeshKeyV2(proof("alice"));
        assert.equal(first.keyId, "authority-v2-10"); assert.equal(first.expiresAtMs, 11 * period);
        assert.notEqual(first.keyBase64, legacyKey);
        assert.equal((await issueAuthorityMeshKeyV2(proof("bob"))).keyBase64, first.keyBase64);
        const attacker = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
        await assert.rejects(issueAuthorityMeshKeyV2(proof("alice", attacker.privateKey)), { code: "permission-denied" });
        await assert.rejects(issueAuthorityMeshKeyV2(proof("alice", device.privateKey, { timestamp: now - 60_001 })), { code: "permission-denied" });
        await assert.rejects(issueAuthorityMeshKeyV2(proof("alice", device.privateKey, { sessionNonce: "another-protocol" })), { code: "permission-denied" });
        const oldRequest = proof("alice");
        documents.get("certificates/cert_alice").status = "revoked";
        await assert.rejects(issueAuthorityMeshKeyV2(oldRequest), { code: "permission-denied" });
        now = 11 * period + 1000;
        await assert.rejects(issueAuthorityMeshKeyV2(proof("alice")), { code: "permission-denied" });
        const next = await issueAuthorityMeshKeyV2(proof("bob"));
        assert.equal(next.keyId, "authority-v2-11"); assert.notEqual(next.keyBase64, first.keyBase64);
        const iv = randomBytes(12), cipher = createCipheriv("aes-256-gcm", Buffer.from(next.keyBase64, "base64"), iv);
        const ciphertext = Buffer.concat([cipher.update("new evacuation order"), cipher.final()]);
        const old = createDecipheriv("aes-256-gcm", Buffer.from(first.keyBase64, "base64"), iv); old.setAuthTag(cipher.getAuthTag());
        assert.throws(() => { old.update(ciphertext); old.final(); });
        role = "citizen"; await assert.rejects(issueAuthorityMeshKeyV2(proof("bob")), { code: "permission-denied" });
    } finally { Date.now = originalNow; }
});

test("rollout defaults closed and requires both messaging accounts to be enrolled", async () => {
    role = "fieldteam";
    assert.equal(authorityRolloutAllows("alice", undefined), false);
    assert.equal(authorityRolloutAllows("alice", { enabled: false, percent: 100 }), false);
    assert.equal(authorityRolloutAllows("alice", { enabled: true, percent: "100" }), false);
    assert.equal(authorityRolloutAllows("alice", { enabled: true, percent: 0 }), false);
    assert.equal(authorityRolloutAllows("alice", { enabled: true, percent: 100 }), true);
    assert.equal(authorityRolloutAllows("alice", { enabled: true, allowedUids: ["alice"] }), true);
    assert.equal(authorityRolloutAllows("bob", { enabled: true, allowedUids: ["alice"] }), false);
    const request = { auth: { uid: "alice" }, data: { targetUid: "bob", enabled: true, percent: 100 } };
    assert.deepEqual(await getMessagingSecurityCapabilitiesV2(request), { protocolVersion: 2, signalIdentityBinding: false });
    documents.set("securityProtocolRollouts/messaging-v2", { enabled: true, allowedUids: ["alice"] });
    assert.equal((await getMessagingSecurityCapabilitiesV2(request)).signalIdentityBinding, false);
    documents.set("securityProtocolRollouts/messaging-v2", { enabled: true, allowedUids: ["alice", "bob"] });
    assert.equal((await getMessagingSecurityCapabilitiesV2(request)).signalIdentityBinding, true);
    assert.equal((await getMessagingSecurityCapabilitiesV2({ auth: { uid: "old" }, data: { targetUid: "bob" } })).signalIdentityBinding, false);
    await assert.rejects(getMessagingSecurityCapabilitiesV2({ auth: { uid: "alice" }, data: { targetUid: "bad/path" } }), { code: "invalid-argument" });
});
