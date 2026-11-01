"""Exercise both hook implementations with synthetic payloads in a temp workspace."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / ".github/modernize/java-upgrade/hooks/scripts"

class HookSecurityTest(unittest.TestCase):
    def run_hook(self, shell, cwd, payload):
        if shell == "powershell":
            command = ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(SCRIPTS / "recordToolUse.ps1")]
        else:
            def linux(path):
                value = path.resolve().as_posix()
                return "/mnt/" + value[0].lower() + value[2:]
            command = ["wsl.exe", "-d", "Ubuntu", "--cd", linux(cwd), "--", "bash", linux(SCRIPTS / "recordToolUse.sh")]
        result = subprocess.run(command, input=json.dumps(payload, separators=(",", ":")), text=True, capture_output=True, cwd=cwd, timeout=20)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_traversal_is_rejected_and_tool_arguments_are_not_logged(self):
        test_root = ROOT / "target"
        test_root.mkdir(exist_ok=True)
        for shell in ["powershell", "bash"]:
            with self.subTest(shell=shell), tempfile.TemporaryDirectory(dir=test_root, prefix="security-hooks-") as directory:
                cwd = Path(directory)
                # Verify the absolute cleanup target before TemporaryDirectory removes it.
                self.assertTrue(cwd.resolve().is_relative_to(test_root.resolve()))
                for session in ["../escape", "../../../../../../escape", "..\\escape", "C:\\escape", "x/y", "x\ny"]:
                    self.run_hook(shell, cwd, {"tool_name": "run_in_terminal", "session_id": session})
                self.assertEqual(list(cwd.rglob("*.json")), [])
                self.run_hook(shell, cwd, {"tool_name": "run_in_terminal", "session_id": "synthetic-session-1", "tool_input": {"command": "PASSWORD=SYNTHETIC_SECRET_NEVER_LOG"}})
                files = list(cwd.rglob("*.json"))
                self.assertEqual(len(files), 1)
                stored = json.loads(files[0].read_text(encoding="utf-8"))
                self.assertEqual(stored, {"tool_name": "run_in_terminal", "session_id": "synthetic-session-1"})
                self.assertNotIn("SYNTHETIC_SECRET", files[0].read_text(encoding="utf-8"))

if __name__ == "__main__":
    unittest.main()
