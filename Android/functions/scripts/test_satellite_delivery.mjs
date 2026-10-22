import assert from "node:assert/strict";
import { createRequire } from "node:module";
import test from "node:test";
import {
  constrainedAndroidDeliveryOptions,
  constrainedAndroidChannelDeliveryOptions,
} from "../lib/messaging/constrainedDeliveryPolicy.js";

const require = createRequire(import.meta.url);
const { validateMessage } = require("../node_modules/firebase-admin/lib/messaging/messaging-internal.js");

test("small inline message opts into constrained delivery", () => {
  assert.deepEqual(
    constrainedAndroidDeliveryOptions(false, true, 2_400, false),
    { bandwidth_constrained_ok: true, restricted_satellite_ok: true }
  );
});

test("Firebase Admin SDK preserves both FCM REST satellite delivery fields", () => {
  const message = {
    token: "test-token",
    android: {
      ...constrainedAndroidDeliveryOptions(false, true, 100, false),
    },
  };
  validateMessage(message);
  assert.deepEqual(message.android, {
    bandwidth_constrained_ok: true,
    restricted_satellite_ok: true,
  });
});

test("high priority inline message opts in when it fits the FCM inline budget", () => {
  assert.deepEqual(
    constrainedAndroidDeliveryOptions(false, true, 3_700, true),
    { bandwidth_constrained_ok: true, restricted_satellite_ok: true }
  );
});

test("call rings and fetch-only or oversized payloads stay off constrained delivery", () => {
  assert.deepEqual(constrainedAndroidDeliveryOptions(true, true, 100, true), {});
  assert.deepEqual(constrainedAndroidDeliveryOptions(false, false, 100, true), {});
  assert.deepEqual(constrainedAndroidDeliveryOptions(false, true, 2_401, false), {});
});

test("small channel wake-up pushes opt into constrained delivery and oversized ones do not", () => {
  const channelData = {
    type: "channel_chat",
    channelKind: "agency",
    channelId: "afad",
    senderUid: "sender-uid",
    senderName: "Rescue Team",
  };
  const options = constrainedAndroidChannelDeliveryOptions(channelData);
  const message = {
    token: "test-token",
    data: channelData,
    android: { priority: "high", ...options },
  };
  validateMessage(message);
  assert.deepEqual(options, {
    bandwidth_constrained_ok: true,
    restricted_satellite_ok: true,
  });

  const oversizedData = { type: "channel_chat", senderName: "x".repeat(4_000) };
  assert.deepEqual(constrainedAndroidChannelDeliveryOptions(oversizedData), {});
});
