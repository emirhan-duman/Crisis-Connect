import assert from "node:assert/strict";
import crypto from "node:crypto";
import {
  deriveConversationId,
  resolveAuthorityMlsPreparationRecipient,
} from "../lib/messaging/authorityMlsPreparationPolicy.js";

const binding = {
  scopeType: "hierarchy",
  channelId: "hc_test",
  participants: ["alice", "bob"],
};
const conversationId = deriveConversationId(binding);
const parent = { version: 2, ...binding };

assert.deepEqual(resolveAuthorityMlsPreparationRecipient(conversationId, "alice", parent), {
  recipientUid: "bob",
  binding,
});
const legacyConversationId = deriveForContext(binding, "cc-authority-mls-conversation:v2");
assert.deepEqual(resolveAuthorityMlsPreparationRecipient(legacyConversationId, "alice", parent), {
  recipientUid: "bob",
  binding,
});
assert.equal(resolveAuthorityMlsPreparationRecipient(conversationId, "mallory", parent), null);
assert.equal(resolveAuthorityMlsPreparationRecipient(conversationId, "alice", { ...parent, channelId: "hc_swapped" }), null);
assert.equal(resolveAuthorityMlsPreparationRecipient(conversationId, "alice", { ...parent, version: 1 }), null);
assert.equal(resolveAuthorityMlsPreparationRecipient(conversationId, "alice", {
  ...parent,
  participants: ["alice", "alice"],
}), null);

console.log("authority MLS preparation policy: ok");

function deriveForContext(value, context) {
  const fields = [context, value.scopeType, value.channelId, String(value.participants.length), ...value.participants];
  const encoded = [];
  for (const field of fields) {
    const bytes = Buffer.from(field, "utf8");
    const length = Buffer.allocUnsafe(4);
    length.writeUInt32BE(bytes.length);
    encoded.push(length, bytes);
  }
  return `am2_${crypto.createHash("sha256").update(Buffer.concat(encoded)).digest("base64url")}`;
}
