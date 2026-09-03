import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from scribe import config, history  # noqa: E402


class HistoryPersistence(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        os.environ["SCRIBE_CONFIG_DIR"] = self._tmp.name

    def tearDown(self):
        os.environ.pop("SCRIBE_CONFIG_DIR", None)
        self._tmp.cleanup()

    def test_history_disabled_by_default(self):
        loaded = config.effective({k: None for k in config.DEFAULTS})
        self.assertFalse(loaded["history_enabled"])

    def test_load_missing_file_is_empty(self):
        self.assertEqual(history.load(), [])

    def test_save_load_round_trip(self):
        entries = [
            {"text": "hello", "seconds": 1.2, "backend": "ct2", "heavy": False},
            {"text": "world", "seconds": 0.4, "backend": "onnx", "heavy": True},
        ]
        history.save(entries)
        self.assertTrue(os.path.exists(history.history_path()))
        self.assertEqual(history.load(), entries)

    def test_prepend_is_newest_first(self):
        out = history.prepend([{"text": "old"}], {"text": "new"})
        self.assertEqual([e["text"] for e in out], ["new", "old"])

    def test_prepend_does_not_mutate_input(self):
        original = [{"text": "old"}]
        history.prepend(original, {"text": "new"})
        self.assertEqual(original, [{"text": "old"}])

    def test_cap_on_prepend(self):
        entries = [{"text": str(i)} for i in range(history.CAP)]
        out = entries
        for i in range(10):
            out = history.prepend(out, {"text": "extra%d" % i})
        self.assertEqual(len(out), history.CAP)
        self.assertEqual(out[0]["text"], "extra9")  # newest kept

    def test_cap_on_save_and_load(self):
        entries = [{"text": str(i)} for i in range(history.CAP + 50)]
        history.save(entries)
        loaded = history.load()
        self.assertEqual(len(loaded), history.CAP)
        # save keeps the first CAP (newest-first ordering preserved)
        self.assertEqual(loaded[0]["text"], "0")

    def test_clear_removes_file(self):
        history.save([{"text": "hi"}])
        history.clear()
        self.assertFalse(os.path.exists(history.history_path()))
        self.assertEqual(history.load(), [])

    def test_clear_missing_file_is_noop(self):
        history.clear()  # must not raise when there's nothing to remove

    def test_corrupt_file_ignored(self):
        with open(history.history_path(), "w", encoding="utf-8") as f:
            f.write("this is not json {{{")
        self.assertEqual(history.load(), [])

    def test_non_list_json_ignored(self):
        with open(history.history_path(), "w", encoding="utf-8") as f:
            f.write('{"text": "not a list"}')
        self.assertEqual(history.load(), [])

    def test_unicode_survives_round_trip(self):
        entries = [{"text": "café — naïve — 日本語"}]
        history.save(entries)
        self.assertEqual(history.load(), entries)


if __name__ == "__main__":
    unittest.main()
