#!/usr/bin/env python3
"""把游戏推到「还要等」状态，然后验证降级唤醒链路。

为什么要有这个脚本：前两次重启验证都作废了，因为**没真的进到等待**就去查闹钟
（0 条闹钟是正确结果，不是 bug）。这次把"推到等待"当成脚本的第一步，
推不到就明确打印"这条验证作废"，不许拿一个空结果当结论。

跑法：python3 tools/device_wait_reboot_check.py            # 推到等待即停
      python3 tools/device_wait_reboot_check.py --reboot   # 推到等待后重启并复查

前置：AVD beifen34 已起，release 包已装（adb install -r -g），
`settings put global auto_time 0`。默认**不动** SCHEDULE_EXACT_ALARM 的 appop，
这样验的是"用户把精确唤醒关了"那条真实存在的路径。
"""

import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "cn.linan.backingup"
ACT = f"{PKG}/{PKG}.MainActivity"
SCREEN_CENTER = (540, 900)
AMBIENCE_HINT = "想让楼里的声音响起来"
WAIT_MARK = "还要等"
CHOICE_HINTS = ("你是谁？", "我在。", "这个号我以前用过？")


def adb(*args: str, check: bool = True) -> str:
    r = subprocess.run(["adb", *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} 失败：{r.stderr.strip()[:200]}")
    return (r.stdout or "").strip()


def ui_nodes() -> list[dict]:
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    out = []
    for node in ET.fromstring(xml).iter("node"):
        text = (node.get("text") or "").strip()
        if not text:
            continue
        b = re.findall(r"-?\d+", node.get("bounds") or "")
        if len(b) != 4:
            continue
        x0, y0, x1, y1 = map(int, b)
        out.append(
            {
                "text": text,
                "center": ((x0 + x1) // 2, (y0 + y1) // 2),
                "clickable": node.get("clickable") == "true",
                "y": y0,
            }
        )
    return out


def tap(x: int, y: int) -> None:
    adb("shell", "input", "tap", str(x), str(y))


def alarms() -> list[str]:
    """只数"还没烧掉的在途闹钟"。

    一条闹钟在 dumpsys 里 = 一个 `*walarm*:<pkg>/.sched.WakeReceiver` tag 行，
    紧跟一行 `type=… origWhen=… window=…`。本包在 dumpsys 里还会以统计行
    （"1 alarms: u0a193:cn.linan.backingup"）出现，拿"包含包名的行数"当结论会算错——
    上一版就是这样把一次货真价实的 PASS 读成了"没重挂"。
    """
    dump = adb("shell", "dumpsys", "alarm").splitlines()
    rows = []
    for i, ln in enumerate(dump):
        if f"*walarm*:{ACT}" not in ln:
            continue
        detail = next(
            (dump[j] for j in range(i + 1, min(i + 6, len(dump))) if "origWhen=" in dump[j]),
            "",
        )
        rows.append(f"{ln.strip()} || {detail.strip()}")
    return rows


def job_rows() -> list[str]:
    dump = adb("shell", "dumpsys", "jobscheduler")
    return [ln.strip() for ln in dump.splitlines() if PKG in ln][:8]


def notifications() -> list[str]:
    dump = adb("shell", "dumpsys", "notification", "--noredact")
    return [ln.strip() for ln in dump.splitlines() if PKG in ln][:20]


def push_to_wait(max_rounds: int = 40) -> bool:
    adb("shell", "am", "start", "-n", ACT)
    time.sleep(2.0)
    for _ in range(max_rounds):
        nodes = ui_nodes()
        if any(WAIT_MARK in n["text"] for n in nodes):
            return True
        # 有选项点选项（选项文本本身不可点，点在它坐标上会命中父级 clickable）。
        # 底部区域里唯一不是选项的东西是那条环境音引导——它整行可点，点上去会弹设置页，
        # 上一版就是栽在这儿：连点 12 轮都在设置页里找按钮。必须显式排除。
        pick = next(
            (
                n
                for n in nodes
                if n["y"] > 1700
                and AMBIENCE_HINT not in n["text"]
                and n["text"] not in ("设置", "挂断")
                and (n["text"] in CHOICE_HINTS or n["text"].endswith(("？", "。")))
            ),
            None,
        )
        if pick is None:
            # 兜底：点正文区域把这一拍的字放完
            tap(*SCREEN_CENTER)
        else:
            tap(*pick["center"])
        time.sleep(2.2)
    return False


def main() -> int:
    reboot = "--reboot" in sys.argv
    print(f"== 目标：{PKG}，精确唤醒 appop={adb('shell', 'appops', 'get', PKG, 'SCHEDULE_EXACT_ALARM')}")
    if not push_to_wait():
        print("没推到等待状态：这条验证作废（不要拿『闹钟 0 条』当结论）")
        return 1
    print("已进入等待（屏上有「还要等」）")
    print("-- 闹钟表 --")
    for r in alarms():
        print("  " + r[:180])
    print("-- 任务表 --")
    for r in job_rows():
        print("  " + r[:180])
    if not reboot:
        return 0
    print("-- 重启 --")
    before = alarms()
    print("重启前本包闹钟行：%d" % len(before))
    adb("reboot")
    adb("wait-for-device")
    # 不能只等 wait-for-device：adb 回来的时候 zygote 都还没起，BOOT_COMPLETED 更远。
    # 上一版的"重启后 0 条闹钟"就是这么读出来的假结果。
    for _ in range(40):
        if adb("shell", "getprop", "sys.boot_completed").strip() == "1":
            break
        time.sleep(3)
    print(f"boot_completed={adb('shell', 'getprop', 'sys.boot_completed')}")
    time.sleep(45)  # BOOT_COMPLETED 广播 → BootReceiver → catchUp 重挂闹钟
    print("-- 重启后的闹钟表 --")
    after = alarms()
    for r in after:
        print("  " + r[:200])

    def due_of(rows: list[str]) -> set[str]:
        # 判据不是"条数"：重启前那条已经烧掉的短等待会让条数虚高。
        # 比的是"在途等待的唤醒时刻（origWhen）有没有一条对得上"。
        return {ln.split("origWhen=")[1].split()[0] for ln in rows if "origWhen=" in ln}

    before_due, after_due = due_of(before), due_of(after)
    kept = before_due & after_due
    print(f"重启前在途唤醒点 {sorted(before_due)} / 重启后 {sorted(after_due)}")
    print(f"→ {'重挂成功（对上的唤醒点：' + ', '.join(sorted(kept)) + '）' if kept else '没重挂，这条 PASS 不了'}")
    adb("shell", "am", "start", "-n", ACT)
    time.sleep(3)
    print("-- 重启后的屏幕 --")
    for n in ui_nodes()[:14]:
        print(f"  {n['text'][:70]}")
    print("-- crash --")
    print(adb("shell", "logcat", "-d", "-b", "crash", "*:E")[:400] or "（空）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
