#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
RUNNER="$ROOT/bin/run-backend.sh"
TEST_ROOT=""
ACTIVE_PIDS=()
SEEN_ARTIFACTS=()
REAL_CP="$(command -v cp)"
REAL_MV="$(command -v mv)"
export REAL_CP REAL_MV

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

cleanup() {
  local pid
  trap - EXIT
  set +u
  for pid in "${ACTIVE_PIDS[@]}"; do
    if kill -0 "$pid" 2>/dev/null; then
      kill -TERM "$pid" 2>/dev/null || true
    fi
  done
  for pid in "${ACTIVE_PIDS[@]}"; do
    wait "$pid" 2>/dev/null || true
  done
  set -u
  if [[ -n "$TEST_ROOT" && -d "$TEST_ROOT" ]]; then
    rm -R -- "$TEST_ROOT"
  fi
}
trap cleanup EXIT

wait_for_file() {
  local path=$1
  local deadline=$((SECONDS + 10))
  while [[ ! -f "$path" ]]; do
    if (( SECONDS >= deadline )); then
      fail "timed out waiting for $path"
    fi
    sleep 0.1
  done
}

wait_for_exit() {
  local pid=$1
  local deadline=$((SECONDS + 10))
  while kill -0 "$pid" 2>/dev/null; do
    if (( SECONDS >= deadline )); then
      fail "process $pid did not exit"
    fi
    sleep 0.1
  done
}

assert_equals() {
  local want=$1
  local got=$2
  local label=$3
  [[ "$got" == "$want" ]] || fail "$label: want '$want', got '$got'"
}

assert_runtime_parent_empty() {
  local found
  found="$(find "$RUNTIME_PARENT" -mindepth 1 -print -quit)"
  [[ -z "$found" ]] || fail "runtime artifact leaked at $found"
}

assert_secret_free_log() {
  local log=$1
  if grep -Fq 'runner-secret-value' "$log"; then
    fail "LLM secret leaked to runner output"
  fi
  if grep -Fq 'government-secret-value' "$log"; then
    fail "government service key leaked to runner output"
  fi
}

record_unique_artifact() {
  local artifact=$1
  local seen
  set +u
  for seen in "${SEEN_ARTIFACTS[@]}"; do
    [[ "$seen" != "$artifact" ]] || fail "runtime artifact path was reused: $artifact"
  done
  set -u
  SEEN_ARTIFACTS+=("$artifact")
}

make_case() {
  local name=$1
  CASE_DIR="$TEST_ROOT/$name"
  CASE_REPO="$CASE_DIR/source worktree"
  CASE_RECORD="$CASE_DIR/record"
  CASE_FAKE_BIN="$CASE_DIR/fake bin"
  CASE_LOG="$CASE_DIR/runner.log"

  mkdir -p "$CASE_REPO/bin" "$CASE_REPO/backend" "$CASE_RECORD" "$CASE_FAKE_BIN"
  cp -- "$RUNNER" "$CASE_REPO/bin/run-backend.sh"
  chmod +x "$CASE_REPO/bin/run-backend.sh"
  printf 'gitdir: test-only\n' > "$CASE_REPO/.git"

  cat > "$CASE_REPO/backend/gradlew" <<'FAKE_GRADLE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$@" > "$TEST_RECORD/gradle.args"
if [[ "${FAKE_GRADLE_FAIL:-0}" == "1" ]]; then
  exit 42
fi
mkdir -p "$(dirname "$0")/build/libs"
printf 'complete-boot-jar\n' > "$(dirname "$0")/build/libs/backend-test.jar"
FAKE_GRADLE
  chmod +x "$CASE_REPO/backend/gradlew"

  cat > "$CASE_FAKE_BIN/git" <<'FAKE_GIT'
#!/usr/bin/env bash
set -euo pipefail
printf 'd6a143a9b3fea571e84f0af12a03d3b5af3b6ee1\n'
FAKE_GIT
  chmod +x "$CASE_FAKE_BIN/git"

  cat > "$CASE_FAKE_BIN/cp" <<'FAKE_CP'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$@" > "$TEST_RECORD/cp.args"
exec "$REAL_CP" "$@"
FAKE_CP
  chmod +x "$CASE_FAKE_BIN/cp"

  cat > "$CASE_FAKE_BIN/mv" <<'FAKE_MV'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$@" > "$TEST_RECORD/mv.args"
exec "$REAL_MV" "$@"
FAKE_MV
  chmod +x "$CASE_FAKE_BIN/mv"

  cat > "$CASE_FAKE_BIN/java" <<'FAKE_JAVA'
#!/usr/bin/env bash
set -euo pipefail

if [[ "${FAKE_JAVA_FAIL:-0}" == "1" ]]; then
  exit 23
fi
[[ "${1:-}" == "-jar" ]] || exit 90
artifact=${2:-}
[[ -f "$artifact" ]] || exit 91
[[ "$(<"$artifact")" == "complete-boot-jar" ]] || exit 92
case "$artifact" in
  *.part) exit 93 ;;
esac
for staged in "$(dirname "$artifact")"/*.part; do
  [[ ! -e "$staged" ]] || exit 94
done

printf '%s\n' "$artifact" > "$TEST_RECORD/java.artifact"
printf '%s\n' "$$" > "$TEST_RECORD/java.pid"
printf 'ready\n' > "$TEST_RECORD/java.ready"

record_signal() {
  printf '%s\n' "$1" > "$TEST_RECORD/java.signal"
  exit "$2"
}
trap 'record_signal HUP 129' HUP
trap 'record_signal INT 130' INT
trap 'record_signal TERM 143' TERM

while :; do
  sleep 1
done
FAKE_JAVA
  chmod +x "$CASE_FAKE_BIN/java"
}

start_case() {
  local port=$1
  PATH="$CASE_FAKE_BIN:$PATH" \
    TMPDIR="$RUNTIME_PARENT" \
    TEST_RECORD="$CASE_RECORD" \
    SERVER_PORT="$port" \
    LLM_API_KEY='runner-secret-value' \
    DATA_GO_KR_SERVICE_KEY='government-secret-value' \
    "$CASE_REPO/bin/run-backend.sh" > "$CASE_LOG" 2>&1 &
  CASE_RUNNER_PID=$!
  ACTIVE_PIDS+=("$CASE_RUNNER_PID")
  wait_for_file "$CASE_RECORD/java.ready"
  wait_for_file "$CASE_RECORD/java.artifact"
}

finish_case() {
  local pid=$1
  local status
  wait_for_exit "$pid"
  if wait "$pid"; then
    status=0
  else
    status=$?
  fi
  CASE_STATUS=$status
}

assert_runner_contract() {
  local port=$1
  local artifact
  artifact="$(<"$CASE_RECORD/java.artifact")"
  record_unique_artifact "$artifact"
  case "$artifact" in
    "$CASE_REPO"/*) fail "runtime artifact stayed inside the source worktree" ;;
  esac
  [[ -f "$artifact" ]] || fail "runtime artifact was absent while child was running"
  assert_equals 'complete-boot-jar' "$(<"$artifact")" "runtime artifact content"
  assert_equals $'--no-daemon\nbootJar' "$(<"$CASE_RECORD/gradle.args")" "Gradle invocation"
  assert_equals \
    $'--\n'"$CASE_REPO/backend/build/libs/backend-test.jar"$'\n'"$artifact.part" \
    "$(<"$CASE_RECORD/cp.args")" \
    "staged artifact copy"
  assert_equals \
    $'--\n'"$artifact.part"$'\n'"$artifact" \
    "$(<"$CASE_RECORD/mv.args")" \
    "atomic artifact publish"
  grep -Fq 'revision=d6a143a9b3fea571e84f0af12a03d3b5af3b6ee1' "$CASE_LOG" \
    || fail "runner did not log the exact revision"
  grep -Fq "artifact=$artifact" "$CASE_LOG" || fail "runner did not log the artifact path"
  grep -Fq "pid=$(<"$CASE_RECORD/java.pid")" "$CASE_LOG" || fail "runner did not log its child PID"
  grep -Fq "port=$port" "$CASE_LOG" || fail "runner did not log the port"
  assert_secret_free_log "$CASE_LOG"
}

test_artifact_and_marker_lifecycle() {
  local artifact child sentinel
  make_case artifact-and-marker
  sleep 60 &
  sentinel=$!
  ACTIVE_PIDS+=("$sentinel")
  start_case 19091
  child="$(<"$CASE_RECORD/java.pid")"
  artifact="$(<"$CASE_RECORD/java.artifact")"
  assert_runner_contract 19091

  mv -- "$CASE_REPO/backend/build" "$CASE_REPO/backend/build-moved"
  [[ -f "$artifact" ]] || fail "runtime artifact depended on source build after launch"
  kill -0 "$child" 2>/dev/null || fail "owned Java child died after source build moved"

  mv -- "$CASE_REPO/.git" "$CASE_REPO/.git-removed"
  wait_for_file "$CASE_RECORD/java.signal"
  assert_equals TERM "$(<"$CASE_RECORD/java.signal")" "worktree disappearance signal"
  finish_case "$CASE_RUNNER_PID"
  (( CASE_STATUS != 0 )) || fail "worktree disappearance returned success"
  kill -0 "$sentinel" 2>/dev/null || fail "runner terminated an unrelated process"
  [[ ! -e "$artifact" ]] || fail "artifact leaked after worktree disappearance"
  assert_runtime_parent_empty

  mv -- "$CASE_REPO/.git-removed" "$CASE_REPO/.git"
  mv -- "$CASE_REPO/backend/build-moved" "$CASE_REPO/backend/build"
  kill -TERM "$sentinel"
  wait "$sentinel" 2>/dev/null || true
  ACTIVE_PIDS=()
}

test_signal_forwarding() {
  local signal=$1
  local expected_status=$2
  local artifact helper helper_status runner_status runner_pid
  make_case "signal-$signal"

  (
    local deadline=$((SECONDS + 10))
    while [[ ! -f "$CASE_RECORD/runner.pid" || ! -f "$CASE_RECORD/java.ready" ]]; do
      if (( SECONDS >= deadline )); then
        printf 'runner or Java did not become ready\n' > "$CASE_RECORD/helper.error"
        exit 1
      fi
      sleep 0.1
    done
    runner_pid="$(<"$CASE_RECORD/runner.pid")"
    kill -s "$signal" "$runner_pid"
    deadline=$((SECONDS + 10))
    while [[ ! -f "$CASE_RECORD/java.signal" ]]; do
      if (( SECONDS >= deadline )); then
        printf '%s was not forwarded\n' "$signal" > "$CASE_RECORD/helper.error"
        kill -TERM "$runner_pid" 2>/dev/null || true
        exit 1
      fi
      sleep 0.1
    done
  ) &
  helper=$!
  ACTIVE_PIDS+=("$helper")

  if PATH="$CASE_FAKE_BIN:$PATH" \
    TMPDIR="$RUNTIME_PARENT" \
    TEST_RECORD="$CASE_RECORD" \
    SERVER_PORT=19092 \
    LLM_API_KEY='runner-secret-value' \
    DATA_GO_KR_SERVICE_KEY='government-secret-value' \
    bash -c 'printf "%s\n" "$$" > "$TEST_RECORD/runner.pid"; exec "$1"' \
      runner-launch "$CASE_REPO/bin/run-backend.sh" > "$CASE_LOG" 2>&1; then
    runner_status=0
  else
    runner_status=$?
  fi

  if wait "$helper"; then
    helper_status=0
  else
    helper_status=$?
  fi
  assert_equals 0 "$helper_status" "$signal signal helper"
  [[ ! -e "$CASE_RECORD/helper.error" ]] || fail "$(<"$CASE_RECORD/helper.error")"
  CASE_STATUS=$runner_status
  wait_for_file "$CASE_RECORD/java.artifact"
  artifact="$(<"$CASE_RECORD/java.artifact")"
  record_unique_artifact "$artifact"
  assert_equals "$signal" "$(<"$CASE_RECORD/java.signal")" "$signal forwarding"
  assert_equals "$expected_status" "$CASE_STATUS" "$signal exit status"
  grep -Fq 'revision=d6a143a9b3fea571e84f0af12a03d3b5af3b6ee1' "$CASE_LOG" \
    || fail "runner did not log the exact revision"
  grep -Fq "artifact=$artifact" "$CASE_LOG" || fail "runner did not log the artifact path"
  grep -Fq "port=19092" "$CASE_LOG" || fail "runner did not log the port"
  assert_secret_free_log "$CASE_LOG"
  [[ ! -e "$artifact" ]] || fail "artifact leaked after $signal"
  assert_runtime_parent_empty
  ACTIVE_PIDS=()
}

test_build_failure_cleanup() {
  local status
  make_case build-failure
  if PATH="$CASE_FAKE_BIN:$PATH" \
    TMPDIR="$RUNTIME_PARENT" \
    TEST_RECORD="$CASE_RECORD" \
    FAKE_GRADLE_FAIL=1 \
    LLM_API_KEY='runner-secret-value' \
    DATA_GO_KR_SERVICE_KEY='government-secret-value' \
    "$CASE_REPO/bin/run-backend.sh" > "$CASE_LOG" 2>&1; then
    status=0
  else
    status=$?
  fi
  assert_equals 42 "$status" "build failure exit status"
  [[ ! -e "$CASE_RECORD/java.pid" ]] || fail "Java started after Gradle failed"
  assert_runtime_parent_empty
  assert_secret_free_log "$CASE_LOG"
}

test_java_failure_cleanup() {
  local status
  make_case java-failure
  if PATH="$CASE_FAKE_BIN:$PATH" \
    TMPDIR="$RUNTIME_PARENT" \
    TEST_RECORD="$CASE_RECORD" \
    FAKE_JAVA_FAIL=1 \
    LLM_API_KEY='runner-secret-value' \
    DATA_GO_KR_SERVICE_KEY='government-secret-value' \
    "$CASE_REPO/bin/run-backend.sh" > "$CASE_LOG" 2>&1; then
    status=0
  else
    status=$?
  fi
  assert_equals 23 "$status" "Java failure exit status"
  assert_runtime_parent_empty
  assert_secret_free_log "$CASE_LOG"
}

[[ -x "$RUNNER" ]] || fail "missing executable runner: $RUNNER"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/mermaid-backend-runner-test.XXXXXX")"
TEST_ROOT="$(cd "$TEST_ROOT" && pwd -P)"
RUNTIME_PARENT="$TEST_ROOT/runtime parent"
mkdir -p "$RUNTIME_PARENT"

test_artifact_and_marker_lifecycle
test_signal_forwarding HUP 129
test_signal_forwarding INT 130
test_signal_forwarding TERM 143
test_build_failure_cleanup
test_java_failure_cleanup

printf 'PASS: backend runtime artifact and owned-child lifecycle contract\n'
