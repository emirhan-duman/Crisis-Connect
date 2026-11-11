/** FCM REST wire fields for delivering eligible Android pushes over constrained paths. */
export function constrainedAndroidDeliveryOptions(
  isCallRing: boolean,
  useInline: boolean,
  dataBytes: number,
  highPriority: boolean
): Record<string, boolean> {
  const eligible = !isCallRing && useInline && (highPriority || dataBytes <= 2_400);
  return eligible
    ? { bandwidth_constrained_ok: true, restricted_satellite_ok: true }
    : {};
}

/** Small channel wake-up pushes are inline routing data and can be delivered on constrained paths. */
export function constrainedAndroidChannelDeliveryOptions(
  data: Record<string, string>
): Record<string, boolean> {
  const dataBytes = Object.entries(data).reduce(
    (total, [key, value]) => total + Buffer.byteLength(key, "utf8") + Buffer.byteLength(value, "utf8"),
    0
  );
  const withinFcmBudget = dataBytes <= 3_800;
  return constrainedAndroidDeliveryOptions(false, withinFcmBudget, dataBytes, true);
}
