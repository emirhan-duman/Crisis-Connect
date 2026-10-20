/** Encrypted message bodies remain opaque. Existing TTL/ring routing fields identify controls. */
export function deferCompactDelivery(
  saving: boolean, ttlMs: number, callRing: boolean, inline = true, highPriority = false
): boolean {
  return saving && (callRing || (ttlMs > 0 && ttlMs <= 5 * 60 * 1000) || (!inline && !highPriority));
}

/** Existing Android/iOS wire contract: transferId.chunkIndex, including the final small chunk. */
export function isAttachmentChunk(id: string): boolean { return /\.\d{1,8}$/.test(id); }
