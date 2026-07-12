# Codex command guard

`hooks.json` runs `hooks/pre_tool_use_policy.py` before Codex Bash and `apply_patch` tool calls.
It blocks only commands already prohibited by this repository: broad staging, direct pushes to
`main`, destructive resets, recursive forced removal, reading the real `.env`, and bypassing the
pre-commit secret guard.

The hook is a guardrail, not a replacement for `.githooks/pre-commit`, CI, or review. Codex loads
project hooks only after this repository is trusted; start a new Codex task after pulling this
branch to activate it.

Verify the policy with:

```bash
python3 .codex/hooks/test_pre_tool_use_policy.py
```
