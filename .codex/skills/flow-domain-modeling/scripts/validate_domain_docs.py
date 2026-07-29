#!/usr/bin/env python3
"""Validate Flow domain-model Markdown documents without third-party packages."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlparse


LINK_PATTERN = re.compile(r"!?\[[^\]]*]\(([^)]+)\)")
FENCE_PATTERN = re.compile(r"^\s*(`{3,}|~{3,})(.*)$")
STRICT_REQUIREMENTS = {
    "适用范围": re.compile(r"^##\s+适用范围与效力\s*$", re.MULTILINE),
    "对象定义": re.compile(
        r"^#{2,3}\s+.*(?:定义|对象角色|领域模型总览).*$", re.MULTILINE
    ),
    "类图": re.compile(r"```mermaid\s+classDiagram\b", re.MULTILINE),
    "字段": re.compile(r"^#{2,3}\s+.*字段.*$", re.MULTILINE),
    "方法": re.compile(r"^#{2,3}\s+.*方法.*$", re.MULTILINE),
    "不变量": re.compile(r"^##\s+领域不变量\s*$", re.MULTILINE),
    "迁移差距": re.compile(r"^##\s+(?:现有|当前)实现迁移差距\s*$", re.MULTILINE),
    "相关文档": re.compile(r"^##\s+相关文档\s*$", re.MULTILINE),
}


def line_number(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def link_destination(raw: str) -> str:
    raw = raw.strip()
    if raw.startswith("<"):
        closing = raw.find(">")
        return raw[1:closing] if closing >= 0 else raw[1:]
    return raw.split(maxsplit=1)[0]


def mask_fenced_blocks(text: str) -> str:
    masked: list[str] = []
    open_fence: tuple[str, int] | None = None

    for line in text.splitlines(keepends=True):
        match = FENCE_PATTERN.match(line)
        inside = open_fence is not None

        if match:
            marker = match.group(1)
            if open_fence is None:
                open_fence = (marker[0], len(marker))
            elif marker[0] == open_fence[0] and len(marker) >= open_fence[1]:
                open_fence = None
            inside = True

        if inside:
            masked.append("".join("\n" if character == "\n" else " " for character in line))
        else:
            masked.append(line)

    return "".join(masked)


def validate_links(path: Path, text: str) -> list[str]:
    errors: list[str] = []
    visible_text = mask_fenced_blocks(text)
    for match in LINK_PATTERN.finditer(visible_text):
        destination = link_destination(match.group(1))
        if not destination or destination.startswith("#"):
            continue

        parsed = urlparse(destination)
        if parsed.scheme in {"http", "https", "mailto", "data"}:
            continue

        local_path = unquote(parsed.path)
        if not local_path:
            continue

        resolved = (path.parent / local_path).resolve()
        if not resolved.exists():
            line = line_number(visible_text, match.start())
            errors.append(f"{path}:{line}: broken local link: {destination}")
    return errors


def validate_fences(path: Path, text: str) -> list[str]:
    errors: list[str] = []
    open_fence: tuple[str, int, int] | None = None

    for number, line in enumerate(text.splitlines(), start=1):
        match = FENCE_PATTERN.match(line)
        if not match:
            continue

        marker = match.group(1)
        if open_fence is None:
            open_fence = (marker[0], len(marker), number)
            continue

        character, length, opened_at = open_fence
        if marker[0] == character and len(marker) >= length:
            open_fence = None

    if open_fence is not None:
        errors.append(f"{path}:{open_fence[2]}: unclosed Markdown code fence")
    return errors


def validate_file(path: Path, strict: bool) -> list[str]:
    if not path.is_file():
        return [f"{path}: file does not exist"]

    text = path.read_text(encoding="utf-8")
    errors: list[str] = []

    for number, line in enumerate(text.splitlines(), start=1):
        if line.rstrip() != line:
            errors.append(f"{path}:{number}: trailing whitespace")

    errors.extend(validate_fences(path, text))
    errors.extend(validate_links(path, text))

    if strict:
        for label, pattern in STRICT_REQUIREMENTS.items():
            if not pattern.search(text):
                errors.append(f"{path}: missing required domain section: {label}")

        if re.search(
            r"(?:# <领域名称>|<Aggregate>|<Entity>|<Value>|"
            r"<Current\.field>|<Target\.field>|<PREFIX>)",
            text,
        ):
            errors.append(f"{path}: unresolved document-template placeholder")

    return errors


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate Markdown structure and local links in Flow domain documents."
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help="Require the standard domain-model sections and a Mermaid class diagram.",
    )
    parser.add_argument("files", nargs="+", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    errors: list[str] = []
    for path in args.files:
        errors.extend(validate_file(path, args.strict))

    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1

    print(f"Validated {len(args.files)} domain document(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
