#!/bin/sh

set -eu

repo_root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
hook_dir="$repo_root/.githooks"
scratch=$(mktemp -d "${TMPDIR:-/tmp}/mermaid-secret-guard.XXXXXX")
trap 'rm -r "$scratch"' EXIT HUP INT TERM

failures=0
checks=0
api_key_name=API_KEY
password_name=MARIADB_ROOT_PASSWORD
token_name=TOKEN
synthetic_credential=SYNTHETIC_CREDENTIAL_1234567890
synthetic_credential_alt=SYNTHETIC_CREDENTIAL_0987654321
provider_prefix=sk
provider_candidate="${provider_prefix}-SYNTHETIC_KEY_MATERIAL_1234567890"
provider_placeholder="${provider_prefix}-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
api_placeholder=YOUR_API_KEY_PLACEHOLDER_EXAMPLE
token_placeholder=YOUR_TOKEN_PLACEHOLDER_EXAMPLE
change_me_placeholder=change-me-synthetic-placeholder-1234567890

run_case() {
    name=$1
    expected=$2
    path=$3
    content=$4
    case_dir="$scratch/$name"

    mkdir -p "$case_dir/$(dirname "$path")"
    git -C "$case_dir" init -q
    git -C "$case_dir" config user.name "Secret Guard Test"
    git -C "$case_dir" config user.email "secret-guard@example.invalid"
    git -C "$case_dir" config commit.gpgSign false
    git -C "$case_dir" config core.hooksPath "$hook_dir"
    printf '%s\n' "$content" > "$case_dir/$path"
    git -C "$case_dir" add -f -- "$path"

    checks=$((checks + 1))
    if git -C "$case_dir" commit -q -m "test: exercise secret guard" \
        >"$case_dir/commit.out" 2>&1; then
        actual=allow
    elif grep -q "pre-commit blocked this commit" "$case_dir/commit.out"; then
        actual=block
    else
        echo "not ok $checks - $name (commit failed outside the secret guard)"
        sed -n '1,12p' "$case_dir/commit.out"
        failures=$((failures + 1))
        return
    fi

    if [ "$actual" = "$expected" ]; then
        echo "ok $checks - $name"
    else
        echo "not ok $checks - $name (expected $expected, got $actual)"
        failures=$((failures + 1))
    fi
}

# Secret-bearing environment files are forbidden at every repository depth.
run_case nested-env-file block "frontend/.env.production" \
    "PLACEHOLDER=not-needed"
run_case non-ascii-nested-env-file block "설정/.env" \
    "DB_PASS=correct-horse-battery-staple-827361945"

# A placeholder word elsewhere on a line must not excuse a credential-shaped value.
run_case placeholder-comment-bypass block "src/comment-bypass.txt" \
    "$api_key_name=$synthetic_credential # example documentation only"
run_case provider-comment-bypass block "src/provider-comment-bypass.txt" \
    "$provider_candidate # xxxx is the placeholder form"
run_case second-assignment-bypass block "src/second-assignment.txt" \
    "$token_name=$synthetic_credential_alt $token_name=$token_placeholder"
run_case placeholder-first-second-assignment block "src/placeholder-first-second-assignment.txt" \
    "$token_name=$token_placeholder $token_name=$synthetic_credential_alt"

# Repository templates and placeholder-shaped values remain legitimate.
run_case root-env-example allow ".env.example" \
    "$password_name=change-me-locally-too"
run_case nested-env-example allow "templates/.env.example" \
    "$api_key_name=$api_placeholder"
run_case long-change-me-placeholder allow "docs/change-me-placeholder.txt" \
    "$api_key_name=$change_me_placeholder"
run_case exact-example-placeholder allow "docs/example-placeholder.txt" \
    "$api_key_name=example-placeholder-value"
run_case embedded-placeholder-marker block "src/embedded-placeholder.txt" \
    "$api_key_name=credential-example-SYNTHETIC_1234567890"
run_case provider-placeholder allow "docs/provider-example.txt" \
    "$provider_placeholder"

if [ "$failures" -ne 0 ]; then
    echo "$failures of $checks secret-guard checks failed"
    exit 1
fi

echo "all $checks secret-guard checks passed"
