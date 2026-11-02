#!/usr/bin/env python3
"""Compile production Kotlin regex literals with native ICU, as Android does.

Run from any directory: python3 tools/check_icu_regex.py [repository-root]
Requires Python 3 and the system ICU runtime (preinstalled on Ubuntu CI runners).
This checks literal compilation, not dynamically constructed patterns or matching behavior.
"""
import ctypes
import ctypes.util
import json
from pathlib import Path
import re
import sys


class ParseError(ctypes.Structure):
    _fields_ = [
        ("line", ctypes.c_int32),
        ("offset", ctypes.c_int32),
        ("before", ctypes.c_uint16 * 16),
        ("after", ctypes.c_uint16 * 16),
    ]


def main():
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent
    library_name = ctypes.util.find_library("icui18n")
    if not library_name:
        raise SystemExit("Native ICU is required; install your distribution's ICU runtime.")
    library = ctypes.CDLL(library_name)
    version = re.search(r"\.so\.(\d+)", library_name)
    suffix = "_" + version.group(1) if version else ""
    compile_pattern = getattr(library, "uregex_open" + suffix)
    compile_pattern.restype = ctypes.c_void_p
    compile_pattern.argtypes = [
        ctypes.POINTER(ctypes.c_uint16), ctypes.c_int32, ctypes.c_uint32,
        ctypes.POINTER(ParseError), ctypes.POINTER(ctypes.c_int32),
    ]
    close_pattern = getattr(library, "uregex_close" + suffix)
    close_pattern.argtypes = [ctypes.c_void_p]
    literal = re.compile(r'Regex\(\s*(?:"""(?P<raw>.*?)"""|(?P<quoted>"(?:\\.|[^"\\])*"))', re.DOTALL)
    checked = 0
    failures = 0
    for source_root in (root / "core/src/main/kotlin", root / "app/src/main/kotlin"):
        for path in sorted(source_root.rglob("*.kt")):
            source = path.read_text()
            for match in literal.finditer(source):
                pattern = match.group("raw")
                if pattern is None:
                    pattern = json.loads(match.group("quoted"))
                encoded = pattern.encode("utf-16-le")
                buffer = (ctypes.c_uint16 * (len(encoded) // 2)).from_buffer_copy(encoded)
                error = ParseError()
                status = ctypes.c_int32(0)
                compiled = compile_pattern(buffer, len(buffer), 0, ctypes.byref(error), ctypes.byref(status))
                if compiled:
                    close_pattern(compiled)
                checked += 1
                if status.value > 0:
                    failures += 1
                    line = source.count("\n", 0, match.start()) + 1
                    print(f"{path.relative_to(root)}:{line}: ICU error {status.value} at offset {error.offset}: {pattern}")
    if checked == 0:
        raise SystemExit("No production regex literals found; check the repository root.")
    print(f"{library_name}: {checked} regex literals checked, {failures} failures")
    return int(failures != 0)


if __name__ == "__main__":
    sys.exit(main())
