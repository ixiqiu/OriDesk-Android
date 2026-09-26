#!/usr/bin/env python3
"""本机 Android 资源预检。

## 为什么需要这个脚本

本机**没有** Android 工具链（java/gradle/sdkmanager/adb 全缺，
见 docs/04-CICD与验证边界.md §2.4），编译验证只能靠 GitHub Actions 往返，
一轮三到八分钟。而资源层面的错误（XML 不合法、引用了不存在的 `@string/...`）
**完全不需要编译器就能查出来**，让它们占用 CI 往返是纯浪费。

这个脚本只做两件低风险、高命中的事：

1. **XML 良构性**：用 expat 解析每个 XML。
   真实踩过的坑：`<!-- ---------- 标题 ---------- -->` 这种分隔线注释在 XML 里
   **非法**（注释内不允许出现 `--`），AAPT2 报的是
   "The string \"--\" is not permitted within comments"，而报错位置指向某一行，
   不看日志根本想不到是注释格式问题。
2. **资源引用可解析**：检查 `@string/` `@color/` `@drawable/` `@mipmap/` `@style/`
   指向的名字确实有定义。写错一个名字，AAPT2 要等到打包才报。

用法（无需 Android SDK，任意 python3 皆可）：

    python3 android/tools/check_resources.py

退出码非 0 表示有问题，可直接串进任何本地钩子或 CI 步骤。
"""

from __future__ import annotations

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

# 形如 @string/foo、@drawable/bar；跳过 @android:*、?attr/*、以及 @{...} 之类的
# 数据绑定表达式。
REF_RE = re.compile(r"@(?:\+)?(string|color|drawable|mipmap|style|layout)/([A-Za-z0-9_.]+)")
SKIP_PREFIXES = ("@android:", "@*android:")


def main() -> int:
    root = pathlib.Path(__file__).resolve().parents[1]
    main_src = root / "app" / "src" / "main"
    if not main_src.is_dir():
        print(f"找不到 {main_src}", file=sys.stderr)
        return 2

    problems: list[str] = []

    # ---------------------------------------------------------------- 1) XML 良构
    xml_files = sorted(main_src.rglob("*.xml"))
    for path in xml_files:
        try:
            ET.parse(path)
        except ET.ParseError as exc:
            problems.append(f"XML 不合法：{path.relative_to(root)}：{exc}")

    # ---------------------------------------------------------------- 2) 已定义名字
    defined: dict[str, set[str]] = {k: set() for k in ("string", "color", "style")}
    file_resources: dict[str, set[str]] = {"drawable": set(), "mipmap": set(), "layout": set()}

    for path in xml_files:
        rel = path.relative_to(main_src)
        stem = path.stem
        parts = rel.parts
        if parts and parts[0] == "res":
            if "drawable" in str(rel):
                file_resources["drawable"].add(stem)
            if "mipmap" in str(rel):
                file_resources["mipmap"].add(stem)
            if "layout" in str(rel):
                file_resources["layout"].add(stem)

        try:
            tree = ET.parse(path)
        except ET.ParseError:
            continue  # 已在上面报过
        for node in tree.iter():
            tag = node.tag
            name = node.get("name")
            if not name:
                continue
            if tag in ("string", "color", "dimen", "bool", "integer"):
                defined.setdefault(tag, set()).add(name)
            elif tag == "style":
                defined.setdefault("style", set()).add(name)

    known: dict[str, set[str]] = {
        "string": defined.get("string", set()),
        "color": defined.get("color", set()),
        "style": defined.get("style", set()),
        "drawable": file_resources["drawable"],
        "mipmap": file_resources["mipmap"],
        "layout": file_resources["layout"],
    }

    # ---------------------------------------------------------------- 3) 引用检查
    for path in xml_files:
        text = path.read_text(encoding="utf-8")
        for line_no, line in enumerate(text.splitlines(), start=1):
            if line.lstrip().startswith("<!--"):
                continue  # 注释里的示例引用不该被当成真引用
            for kind, name in REF_RE.findall(line):
                if any(p in line for p in SKIP_PREFIXES):
                    continue
                if name not in known.get(kind, set()):
                    problems.append(
                        f"引用未定义：{path.relative_to(root)}:{line_no} "
                        f"→ @{kind}/{name}"
                    )

    if problems:
        print(f"发现 {len(problems)} 个问题：")
        for item in problems:
            print("  ✗", item)
        return 1

    print(f"✅ 资源预检通过（{len(xml_files)} 个 XML：良构 + 引用可解析）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
