#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
ROOT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
SECRETS_DIR="$ROOT_DIR/.secrets"

usage() {
    cat <<'EOF'
Usage: scripts/prepare_google_services.sh [--main-only | --internal-only]

Sources are checked in this order for each target:
1. *_PATH environment variable
2. *_BASE64 environment variable
3. .secrets fallback file
4. Existing target file already present locally

Main target:
  app/google-services.json
  GOOGLE_SERVICES_JSON_PATH
  GOOGLE_SERVICES_JSON_BASE64
  .secrets/google-services.json

Internal target:
  app/src/internal/google-services.json
  GOOGLE_SERVICES_INTERNAL_JSON_PATH
  GOOGLE_SERVICES_INTERNAL_JSON_BASE64
  .secrets/google-services.internal.json
EOF
}

log() {
    printf '%s\n' "$*"
}

fail() {
    printf 'Error: %s\n' "$*" >&2
    exit 1
}

require_python() {
    command -v python3 >/dev/null 2>&1 || fail "python3 is required."
}

same_path() {
    python3 - "$1" "$2" <<'PY'
from pathlib import Path
import sys

first = Path(sys.argv[1]).expanduser().resolve()
second = Path(sys.argv[2]).expanduser().resolve()
print("1" if first == second else "0")
PY
}

validate_json() {
    python3 - "$1" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
with path.open("r", encoding="utf-8") as handle:
    json.load(handle)
PY
}

write_base64_json() {
    local encoded="$1"
    local target="$2"

    python3 - "$encoded" "$target" <<'PY'
import base64
import sys
from pathlib import Path

encoded = "".join(sys.argv[1].split())
target = Path(sys.argv[2])
target.parent.mkdir(parents=True, exist_ok=True)
target.write_bytes(base64.b64decode(encoded, validate=True))
PY
}

copy_json_file() {
    local source="$1"
    local target="$2"

    mkdir -p "$(dirname -- "$target")"
    if [[ "$(same_path "$source" "$target")" == "1" ]]; then
        log "Using existing $target"
    else
        cp "$source" "$target"
        log "Wrote $target from $source"
    fi
    validate_json "$target"
}

prepare_target() {
    local label="$1"
    local target="$2"
    local path_var="$3"
    local base64_var="$4"
    local fallback_file="$5"
    local source_path="${!path_var:-}"
    local source_base64="${!base64_var:-}"

    if [[ -n "$source_path" ]]; then
        [[ -f "$source_path" ]] || fail "$path_var points to a missing file: $source_path"
        copy_json_file "$source_path" "$target"
        return
    fi

    if [[ -n "$source_base64" ]]; then
        mkdir -p "$(dirname -- "$target")"
        write_base64_json "$source_base64" "$target"
        validate_json "$target"
        log "Wrote $target from $base64_var"
        return
    fi

    if [[ -f "$fallback_file" ]]; then
        copy_json_file "$fallback_file" "$target"
        return
    fi

    if [[ -f "$target" ]]; then
        validate_json "$target"
        log "Using existing $target"
        return
    fi

    fail "$label config is missing. Provide $path_var, $base64_var, or $fallback_file."
}

main() {
    require_python

    local mode="all"
    case "${1:-}" in
        "")
            ;;
        --main-only)
            mode="main"
            ;;
        --internal-only)
            mode="internal"
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage >&2
            exit 2
            ;;
    esac

    if [[ "$mode" == "all" || "$mode" == "main" ]]; then
        prepare_target \
            "Main" \
            "$ROOT_DIR/app/google-services.json" \
            "GOOGLE_SERVICES_JSON_PATH" \
            "GOOGLE_SERVICES_JSON_BASE64" \
            "$SECRETS_DIR/google-services.json"
    fi

    if [[ "$mode" == "all" || "$mode" == "internal" ]]; then
        prepare_target \
            "Internal" \
            "$ROOT_DIR/app/src/internal/google-services.json" \
            "GOOGLE_SERVICES_INTERNAL_JSON_PATH" \
            "GOOGLE_SERVICES_INTERNAL_JSON_BASE64" \
            "$SECRETS_DIR/google-services.internal.json"
    fi
}

main "$@"
