// Native WebRTC FrameEncryptor / FrameDecryptor that route every media frame through the shared MLS
// group (OpenMLS — the same crypto the web dashboard runs). org.webrtc exposes per-frame crypto ONLY
// as a native pointer (RtpSender.setFrameEncryptor(long) / RtpReceiver.setFrameDecryptor(long)); there
// is no Java per-frame hook like the browser's RTCRtpScriptTransform. So we implement WebRTC's C++
// webrtc::FrameEncryptorInterface / FrameDecryptorInterface here and hand their raw pointers up to
// Kotlin, which passes them to setFrameEncryptor/Decryptor.
//
// ABI STRATEGY — we do NOT link against libwebrtc (it is a prebuilt binary in the AAR). We re-declare
// the two interfaces plus their rtc::RefCountInterface base with the EXACT same virtual-function
// declaration order and the EXACT same parameter type layouts as WebRTC M124 (the milestone
// stream-webrtc-android 1.3.8 ships — .so source stamp 2024-04-15). Virtual dispatch is by vtable slot
// index and arguments are passed per the type layout, so matching *order + layout* is sufficient;
// the names/namespaces are irrelevant to the ABI (WebRTC calls us through the vtable, never by symbol).
// Both sides are built by the same NDK/libc++ with the Itanium C++ ABI, so identical declarations
// produce identical vtables and calling conventions.
//
// The per-frame crypto itself is the Rust `mls_*_frame` C ABI in liborange_mls_worker.so, resolved via
// dlsym (no build-time link between the two .so files). That Rust path returns EMPTY bytes until the
// MLS group handshake completes (WorkerState.mls_group == None) — exactly like the web — so before the
// group is ready WebRTC receives a recoverable-decrypt result, drops that frame and (for video) can
// request a fresh keyframe instead of feeding an empty "successful" frame to the decoder.

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

#define LOG_TAG "MlsFrameCrypto"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// ============================ Minimal WebRTC ABI shim ============================
// Layout/vtable-compatible re-declarations. See the file header for why this is safe.
namespace shim {

// rtc::RefCountReleaseStatus
enum class RefCountReleaseStatus { kDroppedLastRef, kOtherRefsRemained };

// rtc::RefCountInterface — virtuals declared as: AddRef, Release, then the (protected) virtual dtor.
// That order fixes vtable slots 0=AddRef, 1=Release, 2/3=dtor(complete/deleting).
class RefCountInterface {
 public:
  virtual void AddRef() const = 0;
  virtual RefCountReleaseStatus Release() const = 0;

 protected:
  virtual ~RefCountInterface() {}
};

// rtc::ArrayView<T> (variable-size): a trivially-copyable { T* data_; size_t size_; } — 16 bytes,
// passed in two GP registers, identical to the real one. Only data()/size() are ever touched.
template <typename T>
class ArrayView {
 public:
  T* data_;
  size_t size_;
  T* data() const { return data_; }
  size_t size() const { return size_; }
};

// cricket::MediaType — an unscoped int-width enum; only its width matters to the ABI, not the values.
enum MediaType { MEDIA_TYPE_AUDIO, MEDIA_TYPE_VIDEO, MEDIA_TYPE_DATA, MEDIA_TYPE_UNSUPPORTED };

// webrtc::FrameEncryptorInterface — adds Encrypt (slot 4) then GetMaxCiphertextByteSize (slot 5).
class FrameEncryptorInterface : public RefCountInterface {
 public:
  virtual int Encrypt(MediaType media_type,
                      uint32_t ssrc,
                      ArrayView<const uint8_t> additional_data,
                      ArrayView<const uint8_t> frame,
                      ArrayView<uint8_t> encrypted_frame,
                      size_t* bytes_written) = 0;
  virtual size_t GetMaxCiphertextByteSize(MediaType media_type, size_t frame_size) = 0;

 protected:
  ~FrameEncryptorInterface() override {}
};

// webrtc::FrameDecryptorInterface — Result returned by value (16 bytes, in x0/x1). Decrypt is slot 4,
// GetMaxPlaintextByteSize slot 5.
class FrameDecryptorInterface : public RefCountInterface {
 public:
  // Exact WebRTC M124 order. kFailedToDecrypt was previously omitted, shifting the numeric value of
  // kUnknown and making this ABI declaration diverge from the AAR's interface.
  enum class Status { kOk, kRecoverable, kFailedToDecrypt, kUnknown };
  struct Result {
    Result(Status s, size_t n) : status(s), bytes_written(n) {}
    const Status status;
    const size_t bytes_written;
  };
  virtual Result Decrypt(MediaType media_type,
                         const std::vector<uint32_t>& csrcs,
                         ArrayView<const uint8_t> additional_data,
                         ArrayView<const uint8_t> encrypted_frame,
                         ArrayView<uint8_t> frame) = 0;
  virtual size_t GetMaxPlaintextByteSize(MediaType media_type, size_t encrypted_frame_size) = 0;

 protected:
  ~FrameDecryptorInterface() override {}
};

}  // namespace shim

// ==================== Rust MLS per-frame crypto (dlsym, lazy) ====================
namespace {

using EncryptFn = uint8_t* (*)(const uint8_t*, size_t, size_t*);
using DecryptFn = uint8_t* (*)(const uint8_t*, size_t, size_t*);
using FreeFn = void (*)(uint8_t*, size_t);

EncryptFn g_encrypt = nullptr;
DecryptFn g_decrypt = nullptr;
FreeFn g_free = nullptr;
std::atomic<bool> g_resolved{false};

// Resolve the Rust C ABI once. liborange_mls_worker.so is already loaded by Kotlin's MlsWorker
// (System.loadLibrary), so dlopen returns the same handle; we only need its symbols. Returns false if
// the crypto lib is absent (e.g. an ABI without the Rust .so) — callers then emit empty frames.
bool resolveRust() {
  if (g_resolved.load(std::memory_order_acquire)) {
    return g_encrypt && g_decrypt && g_free;
  }
  void* h = dlopen("liborange_mls_worker.so", RTLD_NOW | RTLD_GLOBAL);
  if (h) {
    g_encrypt = reinterpret_cast<EncryptFn>(dlsym(h, "mls_encrypt_frame"));
    g_decrypt = reinterpret_cast<DecryptFn>(dlsym(h, "mls_decrypt_frame"));
    g_free = reinterpret_cast<FreeFn>(dlsym(h, "mls_free"));
    if (!g_encrypt || !g_decrypt || !g_free) LOGW("mls_* symbols missing in liborange_mls_worker.so");
  } else {
    LOGW("dlopen liborange_mls_worker.so failed: %s", dlerror());
  }
  g_resolved.store(true, std::memory_order_release);
  return g_encrypt && g_decrypt && g_free;
}

// Shared refcount. new'd at 0; WebRTC wraps the raw pointer in scoped_refptr (AddRef → 1) and Releases
// it (→ 0 ⇒ delete) when the sender/receiver drops it. Never delete manually on our side.
template <typename Base>
class RefCounted : public Base {
 public:
  void AddRef() const override { ref_.fetch_add(1, std::memory_order_relaxed); }
  shim::RefCountReleaseStatus Release() const override {
    if (ref_.fetch_sub(1, std::memory_order_acq_rel) == 1) {
      delete this;
      return shim::RefCountReleaseStatus::kDroppedLastRef;
    }
    return shim::RefCountReleaseStatus::kOtherRefsRemained;
  }

 protected:
  ~RefCounted() override = default;

 private:
  mutable std::atomic<int> ref_{0};
};

class MlsEncryptor final : public RefCounted<shim::FrameEncryptorInterface> {
 public:
  int Encrypt(shim::MediaType,
              uint32_t,
              shim::ArrayView<const uint8_t> /*additional_data*/,
              shim::ArrayView<const uint8_t> frame,
              shim::ArrayView<uint8_t> encrypted_frame,
              size_t* bytes_written) override {
    *bytes_written = 0;
    if (!resolveRust()) return 0;  // no crypto lib → emit empty (silence), never crash
    size_t out_len = 0;
    uint8_t* out = g_encrypt(frame.data(), frame.size(), &out_len);
    if (out_len == 0) {  // group not ready yet (or empty input) — emit an empty frame like the web
      if (out) g_free(out, out_len);
      return 0;
    }
    if (out_len > encrypted_frame.size()) {  // must never overflow the caller's buffer
      LOGW("cipher %zu > buf %zu — dropping frame", out_len, encrypted_frame.size());
      g_free(out, out_len);
      return 0;
    }
    memcpy(encrypted_frame.data(), out, out_len);
    g_free(out, out_len);
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
                 const std::vector<uint32_t>& /*csrcs*/,
                 shim::ArrayView<const uint8_t> /*additional_data*/,
                 shim::ArrayView<const uint8_t> encrypted_frame,
                 shim::ArrayView<uint8_t> frame) override {
    // A zero-byte plaintext is not a successfully decrypted media frame. kRecoverable keeps the
    // receiver alive while allowing WebRTC's video pipeline to request a new keyframe; reporting
    // kOk/0 here caused every subsequent VP9 frame to be dropped forever when the first keyframe was
    // received before the MLS group became ready.
    if (!resolveRust()) return Result(Status::kRecoverable, 0);
    size_t out_len = 0;
    uint8_t* out = g_decrypt(encrypted_frame.data(), encrypted_frame.size(), &out_len);
    if (out_len == 0) {
      if (out) g_free(out, out_len);
      return Result(Status::kRecoverable, 0);
    }
    if (out_len > frame.size()) {
      g_free(out, out_len);
      return Result(Status::kRecoverable, 0);
    }
    memcpy(frame.data(), out, out_len);
    g_free(out, out_len);
    return Result(Status::kOk, out_len);
  }

  size_t GetMaxPlaintextByteSize(shim::MediaType, size_t encrypted_frame_size) override {
    return encrypted_frame_size;  // plaintext is always ≤ ciphertext
  }
};

}  // namespace

// ================================ JNI surface ================================
extern "C" {

JNIEXPORT jlong JNICALL
Java_com_auralis_crisisconnect_messaging_call_sfu_MlsFrameCrypto_nativeCreateEncryptor(JNIEnv*, jclass) {
  resolveRust();
  return reinterpret_cast<jlong>(static_cast<shim::FrameEncryptorInterface*>(new MlsEncryptor()));
}

JNIEXPORT jlong JNICALL
Java_com_auralis_crisisconnect_messaging_call_sfu_MlsFrameCrypto_nativeCreateDecryptor(JNIEnv*, jclass) {
  resolveRust();
  return reinterpret_cast<jlong>(static_cast<shim::FrameDecryptorInterface*>(new MlsDecryptor()));
}

}  // extern "C"
