#!/bin/zsh
set -euo pipefail

PROJECT_PATH="${PROJECT_PATH:-Crisis Connect.xcodeproj}"
SCHEME="${SCHEME:-Crisis Connect}"
CONFIGURATION="${CONFIGURATION:-Release}"
TEAM_ID="${TEAM_ID:-XY9479JQWV}"
EXPORT_METHOD="${EXPORT_METHOD:-release-testing}"
EXPORT_DESTINATION="${EXPORT_DESTINATION:-export}"
OUTPUT_ROOT="${OUTPUT_ROOT:-$PWD/release/output}"
TIMESTAMP="${TIMESTAMP:-$(date +%Y%m%d-%H%M%S)}"

case "${EXPORT_METHOD}" in
  debugging|release-testing|app-store-connect|validation)
    ;;
  *)
    echo "Unsupported EXPORT_METHOD: ${EXPORT_METHOD}" >&2
    echo "Supported values: debugging, release-testing, app-store-connect, validation" >&2
    exit 1
    ;;
esac

case "${EXPORT_DESTINATION}" in
  export|upload)
    ;;
  *)
    echo "Unsupported EXPORT_DESTINATION: ${EXPORT_DESTINATION}" >&2
    echo "Supported values: export, upload" >&2
    exit 1
    ;;
esac

mkdir -p "${OUTPUT_ROOT}"

ARCHIVE_PATH="${OUTPUT_ROOT}/CrisisConnect-${EXPORT_METHOD}-${TIMESTAMP}.xcarchive"
EXPORT_PATH="${OUTPUT_ROOT}/CrisisConnect-${EXPORT_METHOD}-${TIMESTAMP}"
RESULT_BUNDLE_PATH="${OUTPUT_ROOT}/CrisisConnect-${EXPORT_METHOD}-${TIMESTAMP}.xcresult"
BUILD_SETTINGS_PATH="${OUTPUT_ROOT}/build-settings-${TIMESTAMP}.txt"
EXPORT_OPTIONS_PLIST="${OUTPUT_ROOT}/ExportOptions-${EXPORT_METHOD}-${TIMESTAMP}.plist"
ARCHIVE_LOG_PATH="${OUTPUT_ROOT}/archive-${EXPORT_METHOD}-${TIMESTAMP}.log"
EXPORT_LOG_PATH="${OUTPUT_ROOT}/export-${EXPORT_METHOD}-${TIMESTAMP}.log"
ARCHIVE_INFO_DUMP_PATH="${OUTPUT_ROOT}/archive-info-${TIMESTAMP}.txt"
ENTITLEMENTS_DUMP_PATH="${OUTPUT_ROOT}/source-entitlements-${TIMESTAMP}.txt"
SIGNING_DUMP_PATH="${OUTPUT_ROOT}/codesign-${TIMESTAMP}.txt"

echo "Collecting Release build settings..."
xcodebuild \
  -project "${PROJECT_PATH}" \
  -scheme "${SCHEME}" \
  -configuration "${CONFIGURATION}" \
  -showBuildSettings \
  > "${BUILD_SETTINGS_PATH}"

trim_setting() {
  awk -F' = ' -v key="$1" '$1 ~ key { print $2; exit }' "${BUILD_SETTINGS_PATH}" | xargs
}

MARKETING_VERSION="$(trim_setting "MARKETING_VERSION")"
BUILD_NUMBER="$(trim_setting "CURRENT_PROJECT_VERSION")"
BUNDLE_ID="$(trim_setting "PRODUCT_BUNDLE_IDENTIFIER")"
ENTITLEMENTS_PATH="$(trim_setting "CODE_SIGN_ENTITLEMENTS")"

if [[ -z "${MARKETING_VERSION}" || -z "${BUILD_NUMBER}" || -z "${BUNDLE_ID}" ]]; then
  echo "Failed to resolve version or bundle identifier from build settings." >&2
  exit 1
fi

cat > "${EXPORT_OPTIONS_PLIST}" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>destination</key>
  <string>${EXPORT_DESTINATION}</string>
  <key>method</key>
  <string>${EXPORT_METHOD}</string>
  <key>signingStyle</key>
  <string>automatic</string>
  <key>stripSwiftSymbols</key>
  <true/>
  <key>teamID</key>
  <string>${TEAM_ID}</string>
EOF

if [[ "${EXPORT_METHOD}" == "app-store-connect" || "${EXPORT_METHOD}" == "validation" ]]; then
  cat >> "${EXPORT_OPTIONS_PLIST}" <<EOF
  <key>manageAppVersionAndBuildNumber</key>
  <false/>
  <key>uploadSymbols</key>
  <true/>
EOF
else
  cat >> "${EXPORT_OPTIONS_PLIST}" <<EOF
  <key>embedOnDemandResourcesAssetPacksInBundle</key>
  <true/>
  <key>thinning</key>
  <string>&lt;none&gt;</string>
EOF
fi

cat >> "${EXPORT_OPTIONS_PLIST}" <<EOF
</dict>
</plist>
EOF

echo "Archiving ${SCHEME} ${MARKETING_VERSION} (${BUILD_NUMBER})..."
xcodebuild \
  -project "${PROJECT_PATH}" \
  -scheme "${SCHEME}" \
  -configuration "${CONFIGURATION}" \
  -destination "generic/platform=iOS" \
  -archivePath "${ARCHIVE_PATH}" \
  -resultBundlePath "${RESULT_BUNDLE_PATH}" \
  -allowProvisioningUpdates \
  archive | tee "${ARCHIVE_LOG_PATH}"

echo "Exporting archive with method=${EXPORT_METHOD} destination=${EXPORT_DESTINATION}..."
xcodebuild \
  -exportArchive \
  -archivePath "${ARCHIVE_PATH}" \
  -exportPath "${EXPORT_PATH}" \
  -exportOptionsPlist "${EXPORT_OPTIONS_PLIST}" \
  -allowProvisioningUpdates | tee "${EXPORT_LOG_PATH}"

if [[ -n "${ENTITLEMENTS_PATH}" && -f "${ENTITLEMENTS_PATH}" ]]; then
  plutil -p "${ENTITLEMENTS_PATH}" > "${ENTITLEMENTS_DUMP_PATH}" || true
fi

if [[ -f "${ARCHIVE_PATH}/Info.plist" ]]; then
  plutil -p "${ARCHIVE_PATH}/Info.plist" > "${ARCHIVE_INFO_DUMP_PATH}" || true
fi

APP_PATH="$(find "${ARCHIVE_PATH}/Products/Applications" -maxdepth 1 -name '*.app' -print -quit || true)"
IPA_PATH="$(find "${EXPORT_PATH}" -maxdepth 1 -name '*.ipa' -print -quit || true)"

if [[ -n "${APP_PATH}" ]]; then
  codesign -dv --verbose=4 "${APP_PATH}" &> "${SIGNING_DUMP_PATH}" || true
fi

if [[ -n "${IPA_PATH}" ]]; then
  shasum -a 256 "${IPA_PATH}" | tee "${IPA_PATH}.sha256"
fi

echo
echo "Release artifact package"
echo "  Bundle ID:        ${BUNDLE_ID}"
echo "  Version:          ${MARKETING_VERSION} (${BUILD_NUMBER})"
echo "  Archive:          ${ARCHIVE_PATH}"
echo "  Export folder:    ${EXPORT_PATH}"
echo "  Result bundle:    ${RESULT_BUNDLE_PATH}"
echo "  Build settings:   ${BUILD_SETTINGS_PATH}"
echo "  Archive log:      ${ARCHIVE_LOG_PATH}"
echo "  Export log:       ${EXPORT_LOG_PATH}"

if [[ -n "${IPA_PATH}" ]]; then
  echo "  IPA:              ${IPA_PATH}"
  echo "  SHA256:           ${IPA_PATH}.sha256"
fi

if [[ -f "${SIGNING_DUMP_PATH}" ]]; then
  echo "  Codesign dump:    ${SIGNING_DUMP_PATH}"
fi

if [[ -f "${ARCHIVE_INFO_DUMP_PATH}" ]]; then
  echo "  Archive info:     ${ARCHIVE_INFO_DUMP_PATH}"
fi

if [[ -f "${ENTITLEMENTS_DUMP_PATH}" ]]; then
  echo "  Entitlements:     ${ENTITLEMENTS_DUMP_PATH}"
fi
