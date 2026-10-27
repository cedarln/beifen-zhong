#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""《正在备份》剧本自检器

校验 docs/02 第七节格式契约 + docs/01 圣经的文本硬标准。
用法：python3 tools/validate_script.py script/act*.json
"""
import json
import re
import sys
import glob
from collections import defaultdict

SIGNALS = {
    "foreground", "background_gt_90s", "screen_off", "night_window", "battery_low",
    "power_save_on", "reenter_3x", "notify_denied", "exact_alarm_granted",
    "device_date", "ended_in_app",
}
METRICS = {"trust", "awareness", "integrity"}
TYPES = {"system", "message", "silence", "letter", "choices", "wait", "ending"}
MAX_BUBBLE = 120
MAX_DELTA = 12
ELLIPSIS_PER_1K = 6

# 契约缺口修补：needs 的合法写法（引擎与剧本作者共用这一份）
NEEDS_VALUE = re.compile(r"^(>=|<=|==|!=|>|<)\s*(-?\d+)$")


def char_len(s):
    return len(re.sub(r"\s", "", s))


BEDS = {"bed_room", "hum_mains", "detail_drip", "anomaly_bitcrush"}


def check_ambience(meta, idset, errs, msgs):
    """meta.ambience：四条声床的编排。区间必须存在、增益必须 0..1、段必须按节点顺序且不重叠"""
    amb = meta.get("ambience")
    if not amb:
        errs.append("meta.ambience 缺失：每章必须交代四条声床的默认增益")
        return
    for b in amb.get("beds", []):
        if b not in BEDS:
            errs.append(f"ambience.beds 有未登记的声床名: {b}（须先补 tools/gen_ambience.sh）")
    for bed, g in (amb.get("default_gains") or {}).items():
        if bed not in BEDS:
            errs.append(f"ambience.default_gains 有未登记声床: {bed}")
        if not (0.0 <= float(g) <= 1.0):
            errs.append(f"ambience.default_gains.{bed}={g} 越界（必须 0..1）")
    order = meta.get("_node_order", [])
    covered = []
    for s in amb.get("segments", []) or []:
        f, t = s.get("from"), s.get("to")
        if f not in idset or t not in idset:
            errs.append(f"ambience 段引用不存在的节点: {f}..{t}")
        for bed, g in (s.get("gain") or {}).items():
            if bed not in BEDS:
                errs.append(f"ambience 段 {f}..{t} 有未登记声床: {bed}")
            if not (0.0 <= float(g) <= 1.0):
                errs.append(f"ambience 段 {f}..{t}.{bed}={g} 越界")
        fm = s.get("fade_ms", amb.get("fade_ms"))
        if fm is not None and not (600 <= int(fm) <= 15000):
            errs.append(f"ambience 段 {f}..{t} fade_ms={fm} 不在 600..15000（快于 0.6s 会被听成切换）")
        if not s.get("note"):
            errs.append(f"ambience 段 {f}..{t} 没写 note：每段声床变化都得说清它为什么变")
        covered.append((f, t))
    if covered:
        msgs.append(f"声床编排 {len(covered)} 段：{covered[0][0]}…{covered[-1][1]}")
    if "d7_n711" in idset:
        seg = [s for s in amb.get("segments", []) or [] if s.get("from") == "d7_n711"]
        if not seg:
            errs.append("d7_n711（那六秒语音）必须有把四条声床全部归零的段")
        else:
            g = seg[0].get("gain", {})
            if any(float(g.get(b, 0)) > 0 for b in BEDS):
                errs.append("d7_n711 段的增益没有全部归零：全篇唯一人声不许有任何东西和它抢")


def check_needs(node_id, needs, errs):
    if needs is None:
        return
    if not isinstance(needs, dict):
        errs.append(f"{node_id}: needs 必须是对象")
        return
    for k, v in needs.items():
        if k in METRICS:
            if not isinstance(v, str) or not NEEDS_VALUE.match(v.strip()):
                errs.append(f"{node_id}: needs.{k}={v!r} 不合法，必须形如 \">= 60\"")
        elif isinstance(v, bool):
            continue  # 布尔 flag 合法：{"flag_name": true}
        elif v in (">=60", "true", "false"):
            errs.append(f"{node_id}: needs.{k} 的裸字符串写法已废弃，请改布尔或比较表达式")
        else:
            continue  # 自由 flag 名，值必须为 bool
            # （若值为字符串且非比较表达式，视为写法不统一）


def validate(path):
    errs, warns, msgs = [], [], []
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except Exception as e:  # noqa
        return [f"{path}: JSON 解析失败 {e}"], []

    nodes = data.get("nodes", [])
    ids = [n["id"] for n in nodes]
    if len(ids) != len(set(ids)):
        for d, c in defaultdict(int, {}).items():
            pass
        seen = set()
        for i in ids:
            if i in seen:
                errs.append(f"重复节点 id: {i}")
            seen.add(i)
    idset = set(ids)
    refs = set()

    total_chars = 0
    ellipsis = 0
    wait_sum = 0

    for n in nodes:
        nid = n.get("id", "<无 id>")
        t = n.get("type")
        if t not in TYPES:
            errs.append(f"{nid}: type={t!r} 不在七种之内")

        texts = n.get("text", [])
        if isinstance(texts, str):
            texts = [texts]
        for s in texts:
            total_chars += char_len(s)
            ellipsis += s.count("……")
            if char_len(s) > MAX_BUBBLE:
                errs.append(f"{nid}: 单条 {char_len(s)} 字 > {MAX_BUBBLE}｜{s[:24]}…")
            if "？？？" in s or "！！！" in s or "!!!" in s:
                errs.append(f"{nid}: 禁用标点堆叠｜{s[:20]}")

        # 条件分支（单条 conditional_line 或数组 conditional_lines）与正文同一套文本硬标准
        for cl in ([n["conditional_line"]] if isinstance(n.get("conditional_line"), dict) else []) + \
                  (n.get("conditional_lines") or []):
            if cl.get("if"):
                for key in ("replace_text", "append_text"):
                    body = cl.get(key) or []
                    if isinstance(body, str):
                        body = [body]
                    for s in body:
                        if char_len(s) > MAX_BUBBLE:
                            errs.append(f"{nid}.{key}: 条件分支单条 {char_len(s)} 字 > {MAX_BUBBLE}")
                        if "？？？" in s or "！！！" in s or "!!!" in s:
                            errs.append(f"{nid}.{key}: 条件分支禁用标点堆叠")

        sig = n.get("signal")
        if sig and sig not in SIGNALS:
            errs.append(f"{nid}: signal={sig!r} 不在白名单（须先补圣经映射表）")
        for key in ("on_grant", "on_deny"):
            if isinstance(n.get(key), dict) and n[key].get("signal"):
                if n[key]["signal"] not in SIGNALS:
                    errs.append(f"{nid}.{key}: signal 不在白名单")

        check_needs(nid, n.get("needs"), errs)
        eff = n.get("effects", {})
        for k, v in eff.items():
            if k not in METRICS:
                errs.append(f"{nid}: effects 键 {k!r} 不是三度量")
            if abs(v) > MAX_DELTA:
                if not n.get("note") and not n.get("reason"):
                    errs.append(f"{nid}: |Δ{k}|={v} 超 {MAX_DELTA} 且未写 note 理由")

        if t == "wait":
            if not n.get("first_line_after_wait"):
                errs.append(f"{nid}: wait 缺 first_line_after_wait（违反'等待可验证承接'）")
            if not n.get("wait_ref"):
                errs.append(f"{nid}: wait 缺 wait_ref，无法与结构文档等待表对账")
            if not n.get("activity"):
                errs.append(f"{nid}: wait 缺 activity（'她在做什么'）")
            wait_sum += n.get("duration_ms", 0)

        # 周年文案池：结局节点带 sample_yearly_lines 就是有周年触发（引擎按"第 N 年"取下标，
        # 第 13 年回到第一条）。契约是"12 条、不可重复"，所以条数与去重在这里钉死，
        # 不能等 YearlyTrigger 在第十三年发现池子只有 4 条。
        pool = n.get("sample_yearly_lines")
        if pool is not None:
            if t != "ending":
                errs.append(f"{nid}: sample_yearly_lines 只能挂在结局节点上")
            if not isinstance(pool, list):
                errs.append(f"{nid}: sample_yearly_lines 必须是数组")
            else:
                if len(pool) != 12:
                    errs.append(f"{nid}: 周年文案池要正好 12 条（12 年后循环），现在 {len(pool)} 条")
                if len(set(pool)) != len(pool):
                    errs.append(f"{nid}: 周年文案池有重复句（'不可重复'是结局的一部分）")
                for s in pool:
                    if not isinstance(s, str) or not s.strip():
                        errs.append(f"{nid}: 周年文案池有空句")
                    elif char_len(s) > MAX_BUBBLE:
                        errs.append(f"{nid}: 周年文案单条 {char_len(s)} 字 > {MAX_BUBBLE}")

        if t == "letter":
            body = "".join(n.get("text", []))
            if char_len(body) > 500:
                errs.append(f"{nid}: 信件 {char_len(body)} 字 > 500")

        if t == "choices":
            ch = n.get("choices", [])
            if len(ch) < 2:
                errs.append(f"{nid}: choices 少于 2 项")
            for i, c in enumerate(ch):
                if not c.get("to"):
                    errs.append(f"{nid}.choice{i}: 缺 to")
                else:
                    refs.add(c["to"])
                e = c.get("effects", {})
                if not e:
                    errs.append(f"{nid}.choice{i}: 无 effects（每个选项必须命中三度量之一）")
                elif not (set(e) & METRICS):
                    errs.append(f"{nid}.choice{i}: effects 未命中三度量")
                for k, v in e.items():
                    if abs(v) > MAX_DELTA:
                        errs.append(f"{nid}.choice{i}: |Δ{k}|={v} 超 {MAX_DELTA}")
                check_needs(f"{nid}.choice{i}", c.get("needs"), errs)

        for key in ("next", "fallback_id", "then", "after"):
            if n.get(key):
                refs.add(n[key])
        for key in ("on_grant", "on_deny"):
            if isinstance(n.get(key), dict) and n[key].get("then"):
                refs.add(n[key]["then"])

    missing = sorted(r for r in refs if r not in idset)
    # 允许跨章出口（下一章入口 / 引擎侧结局 id）：以 _end / ending_ / 前一章前缀标记
    unresolved = [m for m in missing if not (
        m.endswith("_end") or m.startswith("ending_") or re.match(r"^d\d+_", m) and m.split("_")[0] != data["meta"]["chapter_id"]
    )]
    for m in unresolved:
        errs.append(f"引用不存在的节点: {m}")

    # 孤儿检测：除首节点外，无入边
    indeg = defaultdict(int)
    for n in nodes:
        for key in ("next", "fallback_id", "then", "after"):
            if n.get(key):
                indeg[n[key]] += 1
        for c in n.get("choices", []) or []:
            if c.get("to"):
                indeg[c["to"]] += 1
        for key in ("on_grant", "on_deny"):
            if isinstance(n.get(key), dict) and n[key].get("then"):
                indeg[n[key]["then"]] += 1
    first = ids[0] if ids else None
    for n in nodes:
        nid = n["id"]
        if n.get("ui_entry"):
            continue
        if indeg[nid] == 0 and nid != first:
            if n.get("type") != "ending" and not n.get("chapter_end"):
                warns.append(f"{nid}: 无入边（孤儿节点）")

    per_k = (ellipsis / total_chars * 1000) if total_chars else 0
    if per_k > ELLIPSIS_PER_1K:
        warns.append(f"省略号密度 {per_k:.1f}/千字 > {ELLIPSIS_PER_1K}")

    m = data.get("meta", {})
    check_ambience(m, idset, errs, msgs)
    declared = m.get("real_wait_total")
    if wait_sum and declared:
        mins = wait_sum / 60000
        hm = re.findall(r"(\d+)\s*(h|小时|m|分|min)", str(declared))
        want = 0.0
        for val, unit in hm:
            want += int(val) * (60 if unit in ("h", "小时") else 1)
        if want:
            diff = (mins - want) / want * 100
            tag = "PASS" if abs(diff) <= 10 else ("warn" if diff > 0 else "warn")
            line = f"等待合计 {mins:.0f} 分 vs 声明 {want:.0f} 分（{diff:+.1f}%，预算 ±10%）"
            (warns if abs(diff) > 10 else msgs).append(line)

    laugh = 0
    # 规则 8 的『真话』可以在下一条气泡里，所以把全章气泡拍平成序列再判
    flat = []
    for n in nodes:
        for s in n.get("text", []) or []:
            flat.append((n["id"], s))
    for i, (nid, s) in enumerate(flat):
        if "哈哈哈哈" not in s:
            continue
        laugh += 1
        standalone = s.strip().startswith("哈哈哈哈") and len(s) < 14
        if not standalone:
            continue
        nxt = flat[i + 1][1] if i + 1 < len(flat) else ""
        # 下一条必须是一句成话（不是省略号、不是又一句笑）
        if len(nxt) < 6 or nxt.strip() in ("……", "...") or "哈哈哈哈" in nxt:
            warns.append(f"{nid}: '哈哈哈哈'后未跟真话（圣经规则 8）｜{s}→{nxt[:12]!r}")
    return errs, warns + msgs + [f"（信息）节点 {len(nodes)}，字数 {total_chars}，笑 {laugh} 处"]


def main():
    files = sys.argv[1:] or sorted(glob.glob("script/act*.json"))
    fail = False
    for p in files:
        errs, warns = validate(p)
        name = p.split("/")[-1]
        print(f"\n=== {name} ===")
        for e in errs:
            print(f"  [FAIL] {e}")
        for w in warns:
            print(f"  [warn] {w}")
        if not errs:
            print("  PASS：全部硬规则通过")
        else:
            fail = True
    print("\n" + ("存在硬规则违反，需返工。" if fail else "全部文件通过硬规则校验。"))
    return 1 if fail else 0


if __name__ == "__main__":
    sys.exit(main())
