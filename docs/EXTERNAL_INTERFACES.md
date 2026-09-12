# External Interfaces

This document describes the public inputs and outputs of the Crisis Connect project. It is a
reference for integrators and security reviewers; internal classes and implementation details are
documented next to their source.

## Mobile application interface

Crisis Connect is primarily an Android and iOS application. Users interact through the mobile UI
to pair with nearby devices, exchange encrypted messages and media, place calls, download offline
maps, and start or receive SOS and rescue workflows. The feature overview and required device
capabilities are documented in the [main README](../README.md#features). Runtime permissions and
the user-visible capability enabled by each permission are listed in the
[permissions table](../README.md#permissions).

The clients accept these external inputs:

- user actions and content entered in the application;
- QR codes or SPAKE2 short codes used to establish a peer identity;
- Bluetooth Low Energy, GATT, and RFCOMM packets from nearby peers;
- authenticated Firebase responses, Firestore documents, push notifications, and web-dashboard
  API responses;
- device sensors, camera, microphone, and location after the user grants the related permission.

Their outputs are UI and notification state, encrypted peer traffic, authenticated Firebase or
dashboard requests, and user-requested media, location, or SOS transmissions. Network and nearby
messages fail closed when authentication, certificate validation, replay checks, or message
integrity checks fail. The [security design](SECURE_DEVELOPMENT.md) describes those boundaries.

## Firebase callable interface

The deployed backend interface is the set of exports in
[`Android/functions/src/index.ts`](../Android/functions/src/index.ts). Firebase callable functions
use Firebase's callable protocol and return a structured success value or an `HttpsError`. Unless a
source file explicitly documents a public webhook, callers must provide a valid Firebase identity;
sensitive operations additionally require App Check, role claims, certificate state, or a
combination of those controls.

The callable functions are grouped as follows:

| Area | Operations | Primary result |
|:--|:--|:--|
| Device and role trust | attestation challenge; issue, list, validate, and revoke role certificates; issue authority mesh keys | challenge, certificate, validation state, or scoped key material |
| Encrypted messaging | identity-key publication and lookup, contact discovery, relay and acknowledgement, push-token registration, Signal prekeys, agency keys, authority roster and MLS preparation | authenticated metadata or ciphertext relay state |
| Account and safety | account deletion, SOS reporting, phone OTP request and verification | deletion acknowledgement, SOS receipt, or verification result |
| Crisis Sentinel | model manifest and short-lived download URL | validated manifest metadata or expiring URL |

Request fields, validation limits, authorization decisions, and exact response objects are defined
beside each exported handler under [`Android/functions/src`](../Android/functions/src). The source
is the authoritative schema because these endpoints are consumed only by versioned Crisis Connect
clients and are not advertised as a general third-party API.

## HTTP and event-driven interfaces

- `turnCredentials` is an authenticated HTTPS endpoint returning short-lived TURN credentials.
- `twilioSpendAlert` is a restricted provider webhook; invalid provider authentication is rejected.
- Firestore creation handlers deliver notifications for encrypted direct, agency, hierarchy, and
  authority-call records. Scheduled handlers expire queued messages and monitor OTP use.
- Firestore collections are an external data interface. Their permitted reads and writes are
  defined by [`Android/firestore.rules`](../Android/firestore.rules); clients must not treat a
  document path as authorization by itself.

## Nearby wire interfaces

Nearby communication uses versioned application frames carried by BLE/GATT or RFCOMM. Pairing
establishes authenticated key material using QR ECDH or SPAKE2 P-256. Message and media payloads use
authenticated encryption; malformed, unauthenticated, replayed, expired, or oversized frames are
rejected. Android protocol implementations and their tests are under
[`Android/app/src/main`](../Android/app/src/main) and
[`Android/app/src/test`](../Android/app/src/test); the corresponding iOS implementation and
cross-platform vectors are under [`iOS/Crisis Connect`](../iOS/Crisis%20Connect) and
[`iOS/Crisis ConnectTests`](../iOS/Crisis%20ConnectTests).

Compatibility changes to any interface above require tests on both affected sides and a
human-readable entry in [CHANGELOG.md](../CHANGELOG.md).
