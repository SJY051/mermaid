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
newline_env_path=$(printf 'line\nbreak/.env')

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

run_rename_case() {
    name=$1
    case_dir="$scratch/$name"

    mkdir -p "$case_dir/src"
    git -C "$case_dir" init -q
    git -C "$case_dir" config user.name "Secret Guard Test"
    git -C "$case_dir" config user.email "secret-guard@example.invalid"
    git -C "$case_dir" config commit.gpgSign false
    git -C "$case_dir" config core.hooksPath "$hook_dir"
    printf '%s\n' "PLACEHOLDER=not-needed" > "$case_dir/src/config.txt"
    git -C "$case_dir" add -- "src/config.txt"
    git -C "$case_dir" commit -q -m "test: establish rename source"
    mkdir -p "$case_dir/renamed"
    git -C "$case_dir" mv -- "src/config.txt" "renamed/.env"

    checks=$((checks + 1))
    if git -C "$case_dir" commit -q -m "test: exercise renamed secret path" \
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

    if [ "$actual" = block ]; then
        echo "ok $checks - $name"
    else
        echo "not ok $checks - $name (expected block, got $actual)"
        failures=$((failures + 1))
    fi
}

run_type_change_case() {
    name=$1
    case_dir="$scratch/$name"

    mkdir -p "$case_dir/docs"
    git -C "$case_dir" init -q
    git -C "$case_dir" config user.name "Secret Guard Test"
    git -C "$case_dir" config user.email "secret-guard@example.invalid"
    git -C "$case_dir" config commit.gpgSign false
    git -C "$case_dir" config core.hooksPath "$hook_dir"
    printf '%s\n' "safe target" > "$case_dir/target.txt"
    ln -s ../target.txt "$case_dir/docs/value.txt"
    git -C "$case_dir" add -- "target.txt" "docs/value.txt"
    git -C "$case_dir" commit -q -m "test: establish symlink source"
    rm "$case_dir/docs/value.txt"
    printf '%s\n' "$api_key_name=$synthetic_credential" > "$case_dir/docs/value.txt"
    git -C "$case_dir" add -- "docs/value.txt"

    checks=$((checks + 1))
    if git -C "$case_dir" commit -q -m "test: exercise secret type change" \
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

    if [ "$actual" = block ]; then
        echo "ok $checks - $name"
    else
        echo "not ok $checks - $name (expected block, got $actual)"
        failures=$((failures + 1))
    fi
}

run_delete_case() {
    name=$1
    case_dir="$scratch/$name"

    mkdir -p "$case_dir"
    git -C "$case_dir" init -q
    git -C "$case_dir" config user.name "Secret Guard Test"
    git -C "$case_dir" config user.email "secret-guard@example.invalid"
    git -C "$case_dir" config commit.gpgSign false
    printf '%s\n' "PLACEHOLDER=not-needed" > "$case_dir/.env"
    git -C "$case_dir" add -f -- ".env"
    git -C "$case_dir" commit -q -m "test: establish file to delete"
    git -C "$case_dir" config core.hooksPath "$hook_dir"
    rm "$case_dir/.env"
    git -C "$case_dir" add -u -- ".env"

    checks=$((checks + 1))
    if git -C "$case_dir" commit -q -m "test: delete forbidden file" \
        >"$case_dir/commit.out" 2>&1; then
        echo "ok $checks - $name"
    else
        echo "not ok $checks - $name (deleting a file must remain allowed)"
        sed -n '1,12p' "$case_dir/commit.out"
        failures=$((failures + 1))
    fi
}

# Secret-bearing environment files are forbidden at every repository depth.
run_case nested-env-file block "frontend/.env.production" \
    "PLACEHOLDER=not-needed"
run_case non-ascii-nested-env-file block "설정/.env" \
    "DB_PASS=correct-horse-battery-staple-827361945"
run_case newline-nested-env-file block "$newline_env_path" \
    "PLACEHOLDER=not-needed"
run_rename_case renamed-env-file
run_type_change_case type-changed-secret
run_delete_case deleted-env-file

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
