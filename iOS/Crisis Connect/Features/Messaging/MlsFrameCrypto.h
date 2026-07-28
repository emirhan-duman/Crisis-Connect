//
//  MlsFrameCrypto.h
//  Crisis Connect
//
//  Per-frame MLS-E2EE for SFU group calls (see MlsFrameCrypto.mm for the ABI story).
//  Parameters are loosely typed `id` so this header stays importable from the Swift
//  bridging header without dragging WebRTC headers in; pass RTCRtpSender/RTCRtpReceiver.
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface MlsFrameCrypto : NSObject

/// True when the linked WebRTC binary exposes the native frame-crypto hooks
/// (`setFrameEncryptor:` / `setFrameDecryptor:`). Checked at runtime so a future
/// WebRTC package bump that drops them degrades to plaintext-gated-off instead of crashing.
@property (class, readonly) BOOL available;

/// Routes every outgoing frame of `sender`'s track through the shared MLS group
/// (mls_ios_encrypt_frame). Returns NO when the hooks are unavailable.
+ (BOOL)attachEncryptorToSender:(id)sender;

/// Routes every incoming frame of `receiver`'s track through mls_ios_decrypt_frame.
/// Returns NO when the hooks are unavailable.
+ (BOOL)attachDecryptorToReceiver:(id)receiver;

/// Calls the modern `setCodecPreferences:error:` on an RTCRtpTransceiver. Swift's importer
/// collapses that overload with its deprecated sibling under one name and resolves to the
/// deprecated one; Objective-C selectors don't collide, so the call lives here.
/// `codecs` is an array of RTCRtpCodecCapability. Best-effort: errors are ignored (Android's
/// runCatching semantics).
+ (void)applyCodecPreferences:(NSArray *)codecs toTransceiver:(id)transceiver;

/// Live shim-object count (encryptors + decryptors not yet released by WebRTC).
/// Test hook: proves the refcount round-trip through the prebuilt binary.
@property (class, readonly) NSInteger debugLiveInstances;

@end

NS_ASSUME_NONNULL_END
