const assert = require("node:assert/strict");
const fc = require("fast-check");
const {
  deriveConversationId,
  resolveAuthorityMlsPreparationRecipient,
} = require("../lib/messaging/authorityMlsPreparationPolicy.js");

const token = fc.stringMatching(/^[A-Za-z0-9_-]{1,64}$/);
const distinctParticipants = fc
  .tuple(token, token)
  .filter(([first, second]) => first !== second);

assert.equal(
  deriveConversationId({
    scopeType: "agency",
    channelId: "ankara",
    participants: ["u1", "u2"],
  }),
  "am2_vvDRM4CAUnWzulIh43GmvwOHV2so1SHHUbGgYEQA1Rs",
  "the Functions conversation ID must remain compatible with Android and iOS",
);

fc.assert(
  fc.property(
    fc.constantFrom("agency", "hierarchy"),
    token,
    distinctParticipants,
    (scopeType, channelId, participants) => {
      const canonicalParticipants = [...participants].sort((left, right) =>
        Buffer.compare(Buffer.from(left, "utf8"), Buffer.from(right, "utf8")),
      );
      const binding = {
        scopeType,
        channelId,
        participants: canonicalParticipants,
      };
      const conversationId = deriveConversationId(binding);

      for (const callerUid of canonicalParticipants) {
        const result = resolveAuthorityMlsPreparationRecipient(
          conversationId,
          callerUid,
          { version: 2, ...binding },
        );
        assert.ok(result);
        assert.equal(result.recipientUid, canonicalParticipants.find((uid) => uid !== callerUid));
        assert.deepEqual(result.binding, binding);
      }
    },
  ),
  { numRuns: 500 },
);

fc.assert(
  fc.property(
    fc.constantFrom("agency", "hierarchy"),
    token,
    token,
    distinctParticipants,
    (scopeType, channelId, replacementChannelId, participants) => {
      fc.pre(channelId !== replacementChannelId);
      const canonicalParticipants = [...participants].sort((left, right) =>
        Buffer.compare(Buffer.from(left, "utf8"), Buffer.from(right, "utf8")),
      );
      const binding = {
        scopeType,
        channelId,
        participants: canonicalParticipants,
      };
      const conversationId = deriveConversationId(binding);

      assert.equal(
        resolveAuthorityMlsPreparationRecipient(
          conversationId,
          canonicalParticipants[0],
          { version: 2, ...binding, channelId: replacementChannelId },
        ),
        null,
      );
    },
  ),
  { numRuns: 500 },
);

console.log("authority MLS preparation property tests: ok");
