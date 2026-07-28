//
//  MlsFrameCrypto.mm
//  Crisis Connect
//
//  Native WebRTC FrameEncryptor/FrameDecryptor that route every SFU media frame through the
//  shared MLS group (OpenMLS — the same `mls_ops.rs` the web dashboard and Android run), the
//  iOS sibling of Android's mls_frame_crypto.cpp.
//
//  ABI STRATEGY — we do NOT build a custom libwebrtc. The prebuilt stasel/WebRTC M149 binary
//  compiles in the upstream `+Native` category methods `-[RTCRtpSender setFrameEncryptor:]` and
//  `-[RTCRtpReceiver setFrameDecryptor:]` (absent from the shipped public headers but present in
//  the ObjC metadata — verified by disassembly of the exact binary this app links). Each takes a
//  `webrtc::scoped_refptr<FrameEncryptor/DecryptorInterface>` BY VALUE, which under the Itanium
//  C++ ABI is passed INDIRECTLY (pointer to a caller temp) because scoped_refptr is non-trivial.
//  We re-declare those selectors with a layout-twin argument (ShimRefPtr: one pointer, given a
//  user-provided copy ctor/dtor precisely so clang also passes it indirectly), and re-declare the
//  two interfaces with the EXACT M149 virtual layout (branch-heads/7827 headers):
//
//    RefCountInterface:        slot 0 AddRef, slot 1 Release, slots 2-3 dtor pair
//    FrameEncryptorInterface:  + slot 4 Encrypt, slot 5 GetMaxCiphertextByteSize
//    FrameDecryptorInterface:  + slot 4 Decrypt, slot 5 GetMaxPlaintextByteSize
//
//  (M149 passes frame bytes as std::span<uint8_t>, ABI-identical to a {ptr,size} pair; MediaType
//  is an int-width enum class; FrameDecryptor Result is a 16-byte trivially-copyable struct
//  returned in registers.) Slot 0/1 usage and the indirect parameter were CONFIRMED empirically:
//  the binary's setFrameEncryptor: implementation loads the arg from [x2], AddRefs via vtable
//  slot 0, calls the native SetFrameEncryptor, and Releases leftovers via slot 1. Virtual
//  dispatch is by vtable slot and both sides are Itanium/AAPCS64, so matching declaration order
//  and type layout is sufficient; names never enter the ABI.
//
//  The per-frame crypto is the Rust C ABI in liborange_mls_worker.a (rust-mls-ios), statically
//  linked — no dlsym needed (unlike Android's two-.so dance). That Rust path returns EMPTY bytes
//  until the MLS group handshake completes — exactly like the web — so before the group is ready
//  we emit empty frames (silence) and never crash.
//

#import "MlsFrameCrypto.h"

#import <objc/message.h>
#import <objc/runtime.h>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

// ==================== Rust MLS per-frame crypto (liborange_mls_worker.a) ====================
extern "C" {
uint8_t *mls_ios_encrypt_frame(const uint8_t *input, size_t input_len, size_t *out_len);
uint8_t *mls_ios_decrypt_frame(const uint8_t *input, size_t input_len, size_t *out_len);
void mls_ios_free_bytes(uint8_t *ptr, size_t len);
}

// ============================ Minimal WebRTC M149 ABI shim ============================
namespace shim {

// webrtc::RefCountReleaseStatus
enum class RefCountReleaseStatus { kDroppedLastRef, kOtherRefsRemained };

// webrtc::RefCountInterface — virtuals declared as: AddRef, Release, then the (protected)
// virtual dtor. That order fixes vtable slots 0=AddRef, 1=Release, 2/3=dtor(complete/deleting).
class RefCountInterface {
 public:
  virtual void AddRef() const = 0;
  virtual RefCountReleaseStatus Release() const = 0;

 protected:
  virtual ~RefCountInterface() {}
};

// std::span<T> ABI twin: a trivially-copyable { T* data_; size_t size_; } — 16 bytes, passed in
// two GP registers. Only data()/size() are ever touched.
template <typename T>
struct Span {
  T *data_;
  size_t size_;
  T *data() const { return data_; }
  size_t size() const { return size_; }
};

// webrtc::MediaType — int-width scoped enum; only its width matters to the ABI.
enum class MediaType : int { AUDIO, VIDEO, DATA, UNSUPPORTED, ANY };

// webrtc::FrameEncryptorInterface — the override dtor keeps the base slots; Encrypt is the first
// NEW virtual (slot 4), GetMaxCiphertextByteSize the second (slot 5).
class FrameEncryptorInterface : public RefCountInterface {
 public:
  ~FrameEncryptorInterface() override {}
  virtual int Encrypt(MediaType media_type,
                      uint32_t ssrc,
                      Span<const uint8_t> additional_data,
                      Span<const uint8_t> frame,
                      Span<uint8_t> encrypted_frame,
                      size_t *bytes_written) = 0;
  virtual size_t GetMaxCiphertextByteSize(MediaType media_type, size_t frame_size) = 0;
};

// webrtc::FrameDecryptorInterface — Result is 16 bytes trivially copyable, returned in x0/x1.
// Decrypt is slot 4, GetMaxPlaintextByteSize slot 5.
class FrameDecryptorInterface : public RefCountInterface {
 public:
  enum class Status { kOk, kRecoverable, kFailedToDecrypt, kUnknown };
  struct Result {
    Status status;
    size_t bytes_written;
  };
  ~FrameDecryptorInterface() override {}
  virtual Result Decrypt(MediaType media_type,
                         const std::vector<uint32_t> &csrcs,
                         Span<const uint8_t> additional_data,
                         Span<const uint8_t> encrypted_frame,
                         Span<uint8_t> frame) = 0;
  virtual size_t GetMaxPlaintextByteSize(MediaType media_type, size_t encrypted_frame_size) = 0;
};

}  // namespace shim

namespace {

std::atomic<NSInteger> g_liveInstances{0};

// Shared refcount. new'd at 0; the binary's setFrameEncryptor: wrapper AddRefs for the copy the
// native sender keeps (verified in its disassembly), and Releases drop it to 0 ⇒ delete when the
// sender/receiver lets go. Never delete manually on our side.
template <typename Base>
class RefCounted : public Base {
 public:
  RefCounted() { g_liveInstances.fetch_add(1, std::memory_order_relaxed); }

  void AddRef() const override { ref_.fetch_add(1, std::memory_order_relaxed); }
  shim::RefCountReleaseStatus Release() const override {
    if (ref_.fetch_sub(1, std::memory_order_acq_rel) == 1) {
      delete this;
      return shim::RefCountReleaseStatus::kDroppedLastRef;
    }
    return shim::RefCountReleaseStatus::kOtherRefsRemained;
  }

 protected:
  ~RefCounted() override { g_liveInstances.fetch_sub(1, std::memory_order_relaxed); }

 private:
  mutable std::atomic<int> ref_{0};
};

class MlsEncryptor final : public RefCounted<shim::FrameEncryptorInterface> {
 public:
  int Encrypt(shim::MediaType,
              uint32_t,
              shim::Span<const uint8_t> /*additional_data*/,
              shim::Span<const uint8_t> frame,
              shim::Span<uint8_t> encrypted_frame,
              size_t *bytes_written) override {
    *bytes_written = 0;
    size_t out_len = 0;
    uint8_t *out = mls_ios_encrypt_frame(frame.data(), frame.size(), &out_len);
    if (out_len == 0) {  // group not ready yet (or empty input) — emit an empty frame like the web
      if (out) mls_ios_free_bytes(out, out_len);
      return 0;
    }
    if (out_len > encrypted_frame.size()) {  // must never overflow the caller's buffer
      NSLog(@"MlsFrameCrypto: cipher %zu > buf %zu — dropping frame", out_len, encrypted_frame.size());
      mls_ios_free_bytes(out, out_len);
      return 0;
    }
    memcpy(encrypted_frame.data(), out, out_len);
    mls_ios_free_bytes(out, out_len);
    *bytes_written = out_len;
    return 0;
  }

  // Generous upper bound: MLS PrivateMessage framing + Ed25519 per-message signature + AEAD tag.
  size_t GetMaxCiphertextByteSize(shim::MediaType, size_t frame_size) override {
    return frame_size + 1024;
  }
};

class MlsDecryptor final : public RefCounted<shim::FrameDecryptorInterface> {
 public:
  Result Decrypt(shim::MediaType,
                 const std::vector<uint32_t> & /*csrcs*/,
                 shim::Span<const uint8_t> /*additional_data*/,
                 shim::Span<const uint8_t> encrypted_frame,
                 shim::Span<uint8_t> frame) override {
    // Always report kOk (never kFailedToDecrypt) so WebRTC doesn't mute/error the stream on the
    // brief pre-handshake window; a failed/early frame just yields 0 bytes (silence), matching
    // the web and Android.
    size_t out_len = 0;
    uint8_t *out = mls_ios_decrypt_frame(encrypted_frame.data(), encrypted_frame.size(), &out_len);
    if (out_len == 0) {
      if (out) mls_ios_free_bytes(out, out_len);
      return {Status::kOk, 0};
    }
    if (out_len > frame.size()) {
      mls_ios_free_bytes(out, out_len);
      return {Status::kOk, 0};
    }
    memcpy(frame.data(), out, out_len);
    mls_ios_free_bytes(out, out_len);
    return {Status::kOk, out_len};
  }

  size_t GetMaxPlaintextByteSize(shim::MediaType, size_t encrypted_frame_size) override {
    return encrypted_frame_size;  // plaintext is always ≤ ciphertext
  }
};

// Layout twin of webrtc::scoped_refptr<T>: one pointer. The user-provided copy ctor/dtor make it
// non-trivial for the purposes of calls, so clang passes it indirectly — the same ABI class as
// the real scoped_refptr, which the binary's implementation expects (it reads the arg via [x2]).
// The wrapper AddRefs the pointee itself; this temp never owns a reference, so the dtor is a no-op.
struct ShimRefPtr {
  const void *ptr;
  explicit ShimRefPtr(const void *p) : ptr(p) {}
  ShimRefPtr(const ShimRefPtr &other) : ptr(other.ptr) {}
  ShimRefPtr &operator=(const ShimRefPtr &) = delete;
  ~ShimRefPtr() {}
};

}  // namespace

// The `+Native` selectors compiled into the prebuilt WebRTC binary. Interface-only category on
// NSObject: it just teaches clang the selector signatures (and thus the indirect argument ABI);
// at runtime the messages resolve on RTCRtpSender / RTCRtpReceiver.
@interface NSObject (CrisisConnectWebRtcNativeHooks)
- (void)setFrameEncryptor:(ShimRefPtr)frameEncryptor;
- (void)setFrameDecryptor:(ShimRefPtr)frameDecryptor;
- (BOOL)setCodecPreferences:(NSArray *_Nullable)codecs error:(NSError *_Nullable *_Nullable)error;
@end

@implementation MlsFrameCrypto

+ (BOOL)available {
  static BOOL available;
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    Class sender = NSClassFromString(@"RTCRtpSender");
    Class receiver = NSClassFromString(@"RTCRtpReceiver");
    available = sender != nil && receiver != nil &&
        [sender instancesRespondToSelector:@selector(setFrameEncryptor:)] &&
        [receiver instancesRespondToSelector:@selector(setFrameDecryptor:)];
  });
  return available;
}

+ (BOOL)attachEncryptorToSender:(id)sender {
  if (!self.available || sender == nil ||
      ![sender respondsToSelector:@selector(setFrameEncryptor:)]) {
    return NO;
  }
  auto *encryptor = static_cast<shim::FrameEncryptorInterface *>(new MlsEncryptor());
  [sender setFrameEncryptor:ShimRefPtr(encryptor)];
  return YES;
}

+ (BOOL)attachDecryptorToReceiver:(id)receiver {
  if (!self.available || receiver == nil ||
      ![receiver respondsToSelector:@selector(setFrameDecryptor:)]) {
    return NO;
  }
  auto *decryptor = static_cast<shim::FrameDecryptorInterface *>(new MlsDecryptor());
  [receiver setFrameDecryptor:ShimRefPtr(decryptor)];
  return YES;
}

+ (void)applyCodecPreferences:(NSArray *)codecs toTransceiver:(id)transceiver {
  if (transceiver == nil ||
      ![transceiver respondsToSelector:@selector(setCodecPreferences:error:)]) {
    return;
  }
  NSError *error = nil;
  if (![transceiver setCodecPreferences:codecs error:&error]) {
    NSLog(@"MlsFrameCrypto: setCodecPreferences failed (keeping defaults): %@", error);
  }
}

+ (NSInteger)debugLiveInstances {
  return g_liveInstances.load(std::memory_order_relaxed);
}

@end
