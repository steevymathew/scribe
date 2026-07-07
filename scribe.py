#!/usr/bin/env python3
"""Compatibility shim — the code lives in src/scribe/.

Keeps `python scribe.py ...`, the .bat/.sh launchers and scribe.service
working unchanged after the package refactor (ROADMAP Phase 2).
"""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "src"))

from scribe.__main__ import main

if __name__ == "__main__":
    main()
