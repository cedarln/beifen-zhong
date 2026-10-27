#!/usr/bin/env python3
"""真机/模拟器冒烟脚本：按文案点一下，再把屏幕上的文案打出来。

只用于开发自检（task #12 模拟器跑通 D1），不进发布产物。
用法：
  python3 tools/smoke_tap.py "你是谁？"
  python3 tools/smoke_tap.py --dump
"""
import re
import subprocess
import sys
import time

SERIAL = "emulator-5554"


def sh(*args):
    return subprocess.run(args, capture_output=True, text=True, check=True).stdout


def dump():
    sh("adb", "-s", SERIAL, "shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return sh("adb", "-s", SERIAL, "shell", "cat", "/sdcard/ui.xml")


def nodes(xml):
    out = []
    for m in re.finditer(r"<node\b[^>]*>", xml):
        tag = m.group(0)
        text = re.search(r'text="([^"]*)"', tag)
        bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if text and bounds and text.group(1).strip():
            x1, y1, x2, y2 = map(int, bounds.groups())
            out.append((text.group(1), (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def main():
    if len(sys.argv) < 2 or sys.argv[1] == "--dump":
        for t, _, _ in nodes(dump()):
            print(t)
        return
    want = sys.argv[2] if sys.argv[1] == "--tap" else sys.argv[1]
    if sys.argv[1] == "--tap":
        xml = dump()
        hit = [n for n in nodes(xml) if n[0] == want] or [n for n in nodes(xml) if want in n[0]]
        if not hit:
            print(f"未找到文案：{want}")
            for t, _, _ in nodes(xml)[:40]:
                print("  ", t)
            sys.exit(1)
        _, x, y = hit[0]
        sh("adb", "-s", SERIAL, "shell", "input", "tap", str(x), str(y))
        time.sleep(3.5)
        print(f"已点：{want}")
    for t, _, _ in nodes(dump()):
        print(t)


if __name__ == "__main__":
    main()
