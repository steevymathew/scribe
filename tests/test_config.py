import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from scribe import config  # noqa: E402


class ConfigPrecedence(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        os.environ["SCRIBE_CONFIG_DIR"] = self._tmp.name

    def tearDown(self):
        os.environ.pop("SCRIBE_CONFIG_DIR", None)
        self._tmp.cleanup()

    def _no_cli(self):
        return {k: None for k in config.DEFAULTS}

    def test_defaults_when_no_file_and_no_flags(self):
        settings = config.effective(self._no_cli())
        self.assertEqual(settings, config.DEFAULTS)

    def test_file_overrides_defaults(self):
        with open(config.config_path(), "w", encoding="utf-8") as f:
            f.write('model = "base.en"\nbeam_size = 3\n')
        settings = config.effective(self._no_cli())
        self.assertEqual(settings["model"], "base.en")
        self.assertEqual(settings["beam_size"], 3)
        self.assertEqual(settings["hotkey"], config.DEFAULTS["hotkey"])

    def test_cli_overrides_file(self):
        with open(config.config_path(), "w", encoding="utf-8") as f:
            f.write('model = "base.en"\ndevice = "cpu"\n')
        cli = self._no_cli()
        cli["model"] = "tiny.en"
        settings = config.effective(cli)
        self.assertEqual(settings["model"], "tiny.en")   # CLI wins
        self.assertEqual(settings["device"], "cpu")      # file survives

    def test_unknown_file_keys_ignored(self):
        with open(config.config_path(), "w", encoding="utf-8") as f:
            f.write('model = "base.en"\nbogus_key = "x"\n')
        settings = config.effective(self._no_cli())
        self.assertEqual(settings["model"], "base.en")
        self.assertNotIn("bogus_key", settings)

    def test_corrupt_file_ignored(self):
        with open(config.config_path(), "w", encoding="utf-8") as f:
            f.write("this is not toml [[[")
        settings = config.effective(self._no_cli())
        self.assertEqual(settings, config.DEFAULTS)

    def test_save_round_trip(self):
        settings = dict(config.DEFAULTS)
        settings["model"] = "medium.en"
        settings["advanced"] = True
        settings["beam_size"] = 5
        config.save(settings)
        loaded = config.effective(self._no_cli())
        self.assertEqual(loaded["model"], "medium.en")
        self.assertTrue(loaded["advanced"])
        self.assertEqual(loaded["beam_size"], 5)

    def test_dictionary_table_round_trip(self):
        settings = dict(config.DEFAULTS)
        settings["dictionary"] = {
            "jira": "Jira",
            "doctor smith": "Dr. Smith",
            'quo"te': 'va"lue',  # quotes must survive escaping
        }
        config.save(settings)
        loaded = config.effective(self._no_cli())
        self.assertEqual(loaded["dictionary"], settings["dictionary"])

    def test_dictionary_loaded_from_file_section(self):
        with open(config.config_path(), "w", encoding="utf-8") as f:
            f.write('model = "base.en"\n\n[dictionary]\njira = "Jira"\n')
        loaded = config.effective(self._no_cli())
        self.assertEqual(loaded["dictionary"], {"jira": "Jira"})
        self.assertEqual(loaded["model"], "base.en")

    def test_empty_dictionary_default(self):
        loaded = config.effective(self._no_cli())
        self.assertEqual(loaded["dictionary"], {})
        self.assertFalse(loaded["remove_fillers"])
        self.assertEqual(loaded["language"], "en")

    def test_defaults_dict_not_shared(self):
        # Mutating an effective() result must never leak into DEFAULTS.
        loaded = config.effective(self._no_cli())
        loaded["dictionary"]["x"] = "y"
        self.assertEqual(config.DEFAULTS["dictionary"], {})


if __name__ == "__main__":
    unittest.main()
