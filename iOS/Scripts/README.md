# Crisis Connect — Scripts

## `patch-firebase-async-let.sh`

Firebase iOS SDK'sındaki bir Swift 6.1 compiler bug'ına geçici workaround uygular.

### Sorun

Firebase Functions SDK `FunctionsContext.swift:40-42`'de 3 tane `async let`
kullanıyor:

```swift
async let authToken = auth?.getToken(forcingRefresh: false)
async let appCheckToken = getAppCheckToken(options: options)
async let limitedUseAppCheckToken = getLimitedUseAppCheckToken(options: options)
```

Xcode 26.3+ / Swift 6.1 toolchain'da bu pattern LLVM SimplifyCFG optimizasyonu
ile etkileşime girip, release build'lerde **Cloud Functions çağrısı sırasında**
aşağıdaki crash'e yol açıyor:

```
swift::swift_Concurrency_fatalError
→ swift::_swift_task_dealloc_specific
→ asyncLet_finish_after_task_completion
→ abort() called — "freed pointer was not the last allocation"
```

Crash App Store / TestFlight / Distribution imzalı build'lerde non-deterministic
olarak tetikleniyor (binary layout'a duyarlı). Debug ve Development imzalı
Release build'lerde genelde görünmüyor, App Store sürümünde çöküyor.

### Referanslar
- firebase/firebase-ios-sdk **#15994** — https://github.com/firebase/firebase-ios-sdk/issues/15994
- swiftlang/swift **#81771** — https://github.com/swiftlang/swift/issues/81771
- Swift Forums — https://forums.swift.org/t/fix-for-async-let-teardown-ordering-crash/85049
- Swift PR **#87571** (upstream fix — henüz merge olmamış)

### Çözüm

Script `async let` bloğunu sequential `await`'e çeviriyor — aynı
semantic, farklı kod yolu, bug tetiklenmiyor.

Öncesi:
```swift
async let authToken = auth?.getToken(forcingRefresh: false)
async let appCheckToken = getAppCheckToken(options: options)
async let limitedUseAppCheckToken = getLimitedUseAppCheckToken(options: options)

return try await FunctionsContext(authToken: authToken, ...)
```

Sonrası:
```swift
let authToken = try await auth?.getToken(forcingRefresh: false)
let appCheckToken = await getAppCheckToken(options: options)
let limitedUseAppCheckToken = await getLimitedUseAppCheckToken(options: options)

return FunctionsContext(authToken: authToken, ...)
```

Tek fark: üç token paralel yerine sıralı çekiliyor (milisaniye farkı).

### Ne zaman çalıştırmalı?

Aşağıdaki durumlardan biri sonrası **App Store / TestFlight build almadan önce**:

1. **`File → Packages → Reset Package Caches`** yaptıysan
2. **`File → Packages → Resolve Package Versions`** yaptıysan
3. **`~/Library/Developer/Xcode/DerivedData`** sildiysen
4. **Firebase iOS SDK güncellediğin** hemen sonra
5. **Yeni bir Mac'te clone ettiğinde** (ilk build öncesi)

### Nasıl çalıştırırım?

Terminal'de proje klasöründen:

```bash
./Scripts/patch-firebase-async-let.sh
```

Sonuçlar:
- `==> Patch applied successfully` — uygulandı, build yapabilirsin
- `==> Already patched, skipping` — zaten yerinde
- `warning: FunctionsContext.swift not found` — önce Xcode'da projeyi aç,
  packages resolve olsun, sonra tekrar dene
- `warning: Expected async let pattern not found` — Firebase SDK güncellenmiş
  olabilir, patch'in geçerli olduğunu kontrol et

Script idempotent, tekrar tekrar çalıştırabilirsin.

### Kontrol: patch yerinde mi?

```bash
grep -c "async let" ~/Library/Developer/Xcode/DerivedData/Crisis_Connect-*/SourcePackages/checkouts/firebase-ios-sdk/FirebaseFunctions/Sources/Internal/FunctionsContext.swift
```

- `0-2` → patch yerinde (2 = sadece yorumdaki referanslar)
- `3` → patch kaldırılmış, scripti çalıştır

### Ne zaman bu workaround'u kaldırabilirim?

Aşağıdakilerden biri merge olduktan sonra:
- **firebase-ios-sdk#15994** kapanırsa
- **swift#87571** merge olur ve yeni Xcode yayınlanırsa

O noktada:
1. `Scripts/patch-firebase-async-let.sh` sil
2. `Scripts/README.md` sil (bu dosya)
3. `Scripts/` klasörü sil

### Xcode Build Phase olarak çalıştırma neden kapalı?

Build phase olarak eklenince macOS "Operation not permitted" hatası veriyor
(Xcode 26.4+ sandbox kısıtı). Bu yüzden manuel çalıştırılıyor. İleride
sandbox problemine workaround bulunursa tekrar build phase'e eklenebilir.
