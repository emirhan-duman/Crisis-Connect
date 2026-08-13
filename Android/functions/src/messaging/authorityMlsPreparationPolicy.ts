import * as crypto from "crypto";

export const AUTHORITY_MLS_CONVERSATION_ID = /^am2_[A-Za-z0-9_-]{43}$/;

export interface AuthorityMlsPreparationBinding {
  scopeType: "agency" | "hierarchy";
  channelId: string;
  participants: [string, string];
}

/**
 * Revalidates the immutable MLS parent instead of trusting caller-supplied routing fields.
 * Returns the other participant only when the document id is the canonical hash of the binding.
 */
export function resolveAuthorityMlsPreparationRecipient(
  conversationId: string,
  callerUid: string,
  raw: Record<string, unknown>,
): { recipientUid: string; binding: AuthorityMlsPreparationBinding } | null {
  if (!AUTHORITY_MLS_CONVERSATION_ID.test(conversationId) || raw.version !== 2) return null;
  if (raw.scopeType !== "agency" && raw.scopeType !== "hierarchy") return null;
  const channelId = bounded(raw.channelId, 256);
  if (!channelId || !Array.isArray(raw.participants) || raw.participants.length !== 2) return null;
  const first = bounded(raw.participants[0], 256);
  const second = bounded(raw.participants[1], 256);
  if (!first || !second || first === second) return null;
  const participants = [first, second];
  participants.sort(compareUtf8);
  if (!participants.includes(callerUid)) return null;
  const binding: AuthorityMlsPreparationBinding = {
    scopeType: raw.scopeType,
    channelId,
    participants: participants as [string, string],
  };
  if (deriveConversationId(binding) !== conversationId &&
      deriveLegacyConversationId(binding) !== conversationId &&
      deriveRetiredConversationId(binding) !== conversationId &&
      deriveAncientConversationId(binding) !== conversationId &&
      derivePrehistoricConversationId(binding) !== conversationId &&
      derivePrimordialConversationId(binding) !== conversationId) {
    return null;
  }
  return { recipientUid: participants[0] === callerUid ? participants[1] : participants[0], binding };
}

export function deriveConversationId(binding: AuthorityMlsPreparationBinding): string {
  return deriveConversationIdForContext(binding, "cc-authority-mls-conversation:v7");
}

function deriveLegacyConversationId(binding: AuthorityMlsPreparationBinding): string {
  return deriveConversationIdForContext(binding, "cc-authority-mls-conversation:v6");
}

function deriveRetiredConversationId(binding: AuthorityMlsPreparationBinding): string {
  return deriveConversationIdForContext(binding, "cc-authority-mls-conversation:v5");
}

function deriveAncientConversationId(binding: AuthorityMlsPreparationBinding): string {
  return deriveConversationIdForContext(binding, "cc-authority-mls-conversation:v4");
}

function derivePrehistoricConversationId(binding: AuthorityMlsPreparationBinding): string {
  return deriveConversationIdForContext(binding, "cc-authority-mls-conversation:v3");
}

function derivePrimordialConversationId(binding: AuthorityMlsPreparationBinding): string {
  return deriveConversationIdForContext(binding, "cc-authority-mls-conversation:v2");
}

function deriveConversationIdForContext(binding: AuthorityMlsPreparationBinding, context: string): string {
  const fields = [
    context,
    binding.scopeType,
    binding.channelId,
    String(binding.participants.length),
    ...binding.participants,
  ];
  const encoded: Buffer[] = [];
  for (const field of fields) {
    const bytes = Buffer.from(field, "utf8");
    const length = Buffer.allocUnsafe(4);
    length.writeUInt32BE(bytes.length);
    encoded.push(length, bytes);
  }
  return `am2_${crypto.createHash("sha256").update(Buffer.concat(encoded)).digest("base64url")}`;
}

function bounded(value: unknown, maxBytes: number): string | null {
  if (typeof value !== "string") return null;
  const normalized = value.trim();
  if (!normalized || Buffer.byteLength(normalized, "utf8") > maxBytes || /[\u0000-\u001f\u007f]/.test(normalized)) {
    return null;
  }
  return normalized;
}

function compareUtf8(left: string, right: string): number {
  return Buffer.compare(Buffer.from(left, "utf8"), Buffer.from(right, "utf8"));
}
