import importlib.util
import unittest
from pathlib import Path


POLICY_PATH = Path(__file__).with_name("pre_tool_use_policy.py")
SPEC = importlib.util.spec_from_file_location("pre_tool_use_policy", POLICY_PATH)
policy = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(policy)


class PreToolUsePolicyTest(unittest.TestCase):
    def test_blocks_repository_safety_violations(self):
        commands = [
            "git add -A",
            "git add .",
            "git push origin main",
            "git push origin HEAD:main",
            "git push origin HEAD:refs/heads/main",
            "git reset --hard HEAD",
            "git reset --hard=HEAD",
            "git checkout -- backend/src/main/java/App.java",
            "rm -fr backend/build",
            "rm backend/build -rf",
            "rm --recursive --force backend/build",
            "cat .env",
            "sed -n '1p' .env",
            "git diff .env",
            "git commit --no-verify -m 'skip guard'",
            "git commit -n -m 'skip guard'",
            "git commit -nm 'skip guard'",
            "git add -Av",
        ]

        for command in commands:
            with self.subTest(command=command):
                self.assertIsNotNone(policy.block_reason(command))

    def test_allows_targeted_safe_commands(self):
        commands = [
            "git add backend/src/main/java/com/mermaid/facility/PharmacyApiClient.java",
            "git push -u origin chore/DEV-611-codex-command-guard",
            "cat .env.example",
            "rm backend/build/generated.txt",
            "git checkout main -- README.md",
            "./gradlew test",
        ]

        for command in commands:
            with self.subTest(command=command):
                self.assertIsNone(policy.block_reason(command))

    def test_denial_uses_the_codex_pre_tool_use_shape(self):
        response = policy.denial("blocked")
        self.assertEqual(response["hookSpecificOutput"]["hookEventName"], "PreToolUse")
        self.assertEqual(response["hookSpecificOutput"]["permissionDecision"], "deny")


if __name__ == "__main__":
    unittest.main()
