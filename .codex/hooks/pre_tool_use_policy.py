#!/usr/bin/env python3
"""Block Codex commands that would bypass this repository's safety rules."""

import json
import shlex
import sys
from typing import Optional


def command_segments(command: str) -> list[list[str]]:
    """Split simple shell command chains without trying to emulate a shell."""
    segments = []
    for part in command.replace("&&", ";").replace("||", ";").replace("|", ";").split(";"):
        try:
            tokens = shlex.split(part)
        except ValueError:
            tokens = part.split()
        if tokens:
            segments.append(tokens)
    return segments


def is_real_env(path: str) -> bool:
    return path == ".env" or path.endswith("/.env")


def has_short_flag(arguments: list[str], flag: str) -> bool:
    return any(
        argument.startswith("-") and not argument.startswith("--") and flag in argument[1:]
        for argument in arguments
    )


def block_reason(command: str) -> Optional[str]:
    for tokens in command_segments(command):
        for index, token in enumerate(tokens):
            if token != "git" or index + 1 >= len(tokens):
                continue

            subcommand = tokens[index + 1]
            arguments = tokens[index + 2 :]

            if subcommand == "add" and (
                "--all" in arguments or has_short_flag(arguments, "A") or "." in arguments
            ):
                return "Broad staging is blocked. Stage the intended files by name."

            if subcommand == "push" and any(
                argument in {"main", "refs/heads/main"}
                or argument.endswith((":main", ":refs/heads/main"))
                for argument in arguments
            ):
                return "Direct pushes to main are blocked. Push a task branch and open a PR."

            if subcommand == "reset" and any(
                argument == "--hard" or argument.startswith("--hard=") for argument in arguments
            ):
                return "git reset --hard is blocked because it can discard unrelated work."

            if subcommand == "checkout" and "--" in arguments:
                separator = arguments.index("--")
                has_source_ref = any(not argument.startswith("-") for argument in arguments[:separator])
                if not has_source_ref:
                    return "git checkout -- is blocked because it can discard unrelated work."

            if subcommand == "commit" and ("--no-verify" in arguments or has_short_flag(arguments, "n")):
                return "Bypassing the pre-commit secret guard is blocked."

            if subcommand in {"diff", "show"} and any(is_real_env(argument) for argument in arguments):
                return "Reading the real .env is blocked. Use .env.example for configuration guidance."

        for index, token in enumerate(tokens):
            if token != "rm":
                continue
            options = []
            for argument in tokens[index + 1 :]:
                if argument == "--":
                    break
                if argument.startswith("-"):
                    options.append(argument)
            compact_options = "".join(option[1:] for option in options if not option.startswith("--"))
            has_recursive = "r" in compact_options or "--recursive" in options
            has_force = "f" in compact_options or "--force" in options
            if has_recursive and has_force:
                return "Recursive forced removal is blocked. Remove only the named file after review."

        readers = {"cat", "less", "more", "head", "tail", "sed", "awk", "grep", "rg", "open"}
        if any(token in readers for token in tokens) and any(is_real_env(token) for token in tokens):
            return "Reading the real .env is blocked. Use .env.example for configuration guidance."

        if tokens[0] in {"source", "."} and any(is_real_env(token) for token in tokens[1:]):
            return "Loading the real .env into a Codex shell is blocked."

    return None


def denial(reason: str) -> dict:
    return {
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }
    }


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except json.JSONDecodeError:
        return 0

    command = payload.get("tool_input", {}).get("command", "")
    if not isinstance(command, str):
        return 0

    reason = block_reason(command)
    if reason:
        print(json.dumps(denial(reason)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
