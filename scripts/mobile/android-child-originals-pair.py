#!/usr/bin/env python3
"""Finite child-originals entry; reuse the reviewed Controller/first/cold worker engine, with no install/build/kill/retry."""
import importlib.util
from pathlib import Path
import sys

sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location("child_originals_shared_pair", Path(__file__).with_name("android-nonempty-foreground-pair.py"))
_engine = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_engine)

if __name__ == "__main__":
    _engine.main(child_originals=True)
