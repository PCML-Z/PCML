#!/usr/bin/env python3
"""Print the Gradle failure as one GitHub Actions error annotation."""

import pathlib
import sys

path = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "gradle-release.log")
text = path.read_text(errors="replace") if path.is_file() else ""
errors = [line for line in text.splitlines() if line.startswith("e: ") or line.startswith("error: ")]
if errors:
    chunk = "\n".join(errors[-12:])
else:
    idx = text.rfind("What went wrong")
    if idx < 0:
        idx = text.rfind("FAILURE:")
    chunk = text[idx:] if idx >= 0 else text[-1800:]
chunk = chunk.replace("::", ":").replace("\r", " ").replace("\n", " | ")
print("::error title=release build::" + chunk[:1800])
