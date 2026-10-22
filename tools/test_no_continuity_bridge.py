"""Source-level guards for the no-bridge comparison build (not runtime tests)."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/me/rerere/rikkahub'


class NoContinuityBridgeTest(unittest.TestCase):
    def test_no_sync_or_prompt_implementation(self):
        removed = ('syncRecentContinuity', 'recentContinuityPrompt',
                   'recentCrossClientContext', 'buildRecentContinuityArguments',
                   'selectRecentContinuityTurns', 'recentContinuityHashes')
        for path in SOURCE.rglob('*.kt'):
            text = path.read_text()
            for symbol in removed:
                self.assertNotIn(symbol, text, str(path))

    def test_endpoint_hidden_and_blocked_before_transport(self):
        text = (SOURCE / 'data/ai/mcp/McpManager.kt').read_text()
        available = text.split('fun getAllAvailableTools(', 1)[1].split('suspend fun callTool(', 1)[0]
        self.assertIn('filterNot', available)
        self.assertIn('tool.name == DISABLED_CONTINUITY_TOOL_NAME', available)
        call = text.split('suspend fun callTool(', 1)[1]
        guard = call.index('if (toolName == DISABLED_CONTINUITY_TOOL_NAME)')
        self.assertLess(guard, call.index('val pair = clients[serverId]'))
        self.assertIn('return listOf(UIMessagePart.Text("Cross-client continuity bridge is disabled', call[:call.index('val pair = clients[serverId]')])


if __name__ == '__main__':
    unittest.main()
