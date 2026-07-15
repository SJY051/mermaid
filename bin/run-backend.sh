#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
WORKTREE_MARKER="$ROOT/.git"
RUNTIME_DIR=""
STAGED_ARTIFACT=""
RUNTIME_ARTIFACT=""
CHILD_PID=""

cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM

  if [[ -n "$CHILD_PID" ]] && kill -0 "$CHILD_PID" 2>/dev/null; then
    kill -TERM "$CHILD_PID" 2>/dev/null || true
    wait "$CHILD_PID" 2>/dev/null || true
  fi
  CHILD_PID=""

  if [[ -n "$STAGED_ARTIFACT" && -e "$STAGED_ARTIFACT" ]]; then
    if ! rm -- "$STAGED_ARTIFACT"; then
      status=1
    fi
  fi
  if [[ -n "$RUNTIME_ARTIFACT" && -e "$RUNTIME_ARTIFACT" ]]; then
    if ! rm -- "$RUNTIME_ARTIFACT"; then
      status=1
    fi
  fi
  if [[ -n "$RUNTIME_DIR" && -d "$RUNTIME_DIR" ]]; then
    if ! rmdir -- "$RUNTIME_DIR"; then
      status=1
    fi
  fi

  exit "$status"
}
trap cleanup EXIT

forward_signal() {
  local signal=$1
  local exit_status=$2
  if [[ -n "$CHILD_PID" ]] && kill -0 "$CHILD_PID" 2>/dev/null; then
    kill -s "$signal" "$CHILD_PID"
  else
    exit "$exit_status"
  fi
}
trap 'forward_signal HUP 129' HUP
trap 'forward_signal INT 130' INT
trap 'forward_signal TERM 143' TERM

if [[ ! -e "$WORKTREE_MARKER" ]]; then
  printf 'backend_runtime_error reason=source-worktree-unavailable\n' >&2
  exit 1
fi

REVISION="$(git -C "$ROOT" rev-parse HEAD)"
PORT="${SERVER_PORT:-8080}"
for argument in "$@"; do
  case "$argument" in
    --server.port=*) PORT=${argument#--server.port=} ;;
  esac
done

(
  cd "$ROOT/backend"
  ./gradlew --no-daemon bootJar
)

SOURCE_ARTIFACT=""
ARTIFACT_COUNT=0
for candidate in "$ROOT"/backend/build/libs/*.jar; do
  [[ -e "$candidate" ]] || continue
  case "$(basename "$candidate")" in
    *-plain.jar) continue ;;
  esac
  SOURCE_ARTIFACT=$candidate
  ARTIFACT_COUNT=$((ARTIFACT_COUNT + 1))
done

if [[ "$ARTIFACT_COUNT" -ne 1 || ! -s "$SOURCE_ARTIFACT" ]]; then
  printf 'backend_runtime_error reason=boot-jar-not-unique count=%s\n' "$ARTIFACT_COUNT" >&2
  exit 1
fi

RUNTIME_PARENT="${TMPDIR:-/tmp}"
RUNTIME_DIR="$(mktemp -d "$RUNTIME_PARENT/mermaid-backend.XXXXXX")"
RUNTIME_DIR="$(cd "$RUNTIME_DIR" && pwd -P)"
case "$RUNTIME_DIR/" in
  "$ROOT/"*)
    printf 'backend_runtime_error reason=runtime-path-inside-worktree\n' >&2
    exit 1
    ;;
esac

STAGED_ARTIFACT="$RUNTIME_DIR/backend.jar.part"
RUNTIME_ARTIFACT="$RUNTIME_DIR/backend.jar"
cp -- "$SOURCE_ARTIFACT" "$STAGED_ARTIFACT"
mv -- "$STAGED_ARTIFACT" "$RUNTIME_ARTIFACT"
STAGED_ARTIFACT=""

# Without job control, non-interactive Bash starts asynchronous children with SIGINT
# ignored. Enable it only for this fork so INT remains forwardable, then restore it.
set -m
java -jar "$RUNTIME_ARTIFACT" "$@" &
CHILD_PID=$!
set +m
printf 'backend_runtime revision=%s artifact=%s pid=%s port=%s\n' \
  "$REVISION" "$RUNTIME_ARTIFACT" "$CHILD_PID" "$PORT"

WORKTREE_REMOVED=false
while kill -0 "$CHILD_PID" 2>/dev/null; do
  if [[ ! -e "$WORKTREE_MARKER" ]]; then
    WORKTREE_REMOVED=true
    printf 'backend_runtime_stopping reason=source-worktree-removed pid=%s\n' "$CHILD_PID"
    kill -TERM "$CHILD_PID" 2>/dev/null || true
    break
  fi
  sleep 0.2
done

if wait "$CHILD_PID"; then
  CHILD_STATUS=0
else
  CHILD_STATUS=$?
fi
CHILD_PID=""

if [[ "$WORKTREE_REMOVED" == true ]]; then
  exit 1
fi
exit "$CHILD_STATUS"
