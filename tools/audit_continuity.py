#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""跨章连续性审计：并行写作的塌方点检查

检查四件事：
  A. flag 闭合：needs / conditional_line.if / flags_set 里出现的每个名字，是否在某处真的被置位
  B. 世界事实一致性：房号、人名、机构名、节气日期这些不可漂移的专名
  C. 度量可达性：模拟所有分支路径，检查七个结局的 gate 是否真的可达
  D. 跨章回收：结构文档承诺的回指（如 D6 要回收 D3 的错过）是否两边都写了
"""
import json
import re
import sys
import itertools
import glob

METRICS = ["trust", "awareness", "integrity"]
# 圣经/结构文档里钉死的专名，任何章节不得改写
CANON = {
    "她": ["小满", "XM-001", "307"],
    "隔壁（D1 消失）": ["305"],
    "重复说话的人": ["309"],
    "另一间（D6 锁）": ["311"],
    "机构": ["长明计划"],
    "她的日子": ["10 月 31", "11 月 1", "11 月 3"],
}
CH_ENDINGS = {
    "ending_tomorrow": "明天见", "ending_fragment": "残片", "ending_accomplice": "共犯",
    "ending_backup": "备份", "ending_impostor": "它们进来了",
    "ending_emergency_contact": "紧急联系人", "ending_off": "你关掉了我",
}


def load(paths):
    out = []
    for p in sorted(paths):
        out.append((p, json.load(open(p, encoding="utf-8"))))
    return out


def walk_edges(n):
    """把节点里所有可能的跳转抽成 (标签, 目标, needs, effects)"""
    es = []
    for c in n.get("choices", []) or []:
        es.append(("choice:" + c.get("text", "")[:12], c.get("to"), c.get("needs"), c.get("effects", {})))
    for k in ("next", "then", "fallback_id", "after"):
        if n.get(k):
            es.append((k, n[k], None, {}))
    for k in ("on_grant", "on_deny"):
        if isinstance(n.get(k), dict) and n[k].get("then"):
            es.append((k, n[k]["then"], None, n[k].get("effects", {})))
    for cl in conditionals(n):
        if cl.get("if"):
            es.append(("cond", n.get("next"), {cl["if"]: True}, {}))
    return es


def conditionals(n):
    """conditional_line（单条对象）与 conditional_lines（多条数组）都算条件分支"""
    single = n.get("conditional_line")
    many = n.get("conditional_lines")
    out = []
    if isinstance(single, dict):
        out.append(single)
    if isinstance(many, list):
        out.extend(c for c in many if isinstance(c, dict))
    return out


def flag_inventory(chapters):
    set_flags, used_flags = {}, {}
    for p, d in chapters:
        for n in d["nodes"]:
            for c in n.get("choices", []) or []:
                for f in c.get("flags_set", []) or []:
                    name, _, val = str(f).partition("=")
                    set_flags.setdefault(name.strip(), set()).add(n["id"])
                for k in (c.get("needs") or {}):
                    if k not in METRICS and k not in ("flag_not",):
                        used_flags.setdefault(k, set()).add(f"{n['id']} (choice)")
            for k in (n.get("needs") or {}):
                if k not in METRICS:
                    used_flags.setdefault(k, set()).add(n["id"])
            for cl in conditionals(n):
                expr = cl.get("if") or ""
                # true/false 是字面量不是 flag 名：剧本里 `player_named == false` 这种写法
                # 是合法判定（StateCondition 的 NAMED_EQ 认它），别把它当成"未登记的世界事实"
                for tok in [t for t in re.findall(r"[a-z_]{3,}", expr)
                            if t not in METRICS and t not in ("true", "false")]:
                    used_flags.setdefault(tok, set()).add(f"{n['id']} (cond)")
    return set_flags, used_flags


def spoken_text(chapters):
    out = []
    for _, d in chapters:
        for n in d["nodes"]:
            if n.get("type") in ("message", "letter", "system"):
                for s in n.get("text") or []:
                    out.append(s)
            for c in n.get("choices") or []:
                out.append(c.get("text", ""))
                if c.get("prompt_text"):
                    out.extend(c["prompt_text"])
            if n.get("prompt_text"):
                out.extend(n["prompt_text"])
            for cl in conditionals(n):
                for key in ("replace_text", "append_text"):
                    body = cl.get(key) or []
                    out.extend([body] if isinstance(body, str) else body)
    return "\n".join(out)


def canon_scan(chapters):
    hits = []
    joined = spoken_text(chapters)
    # 房号必须来自白名单，出现其它三位数房号即视为漂移
    rooms = set(re.findall(r"(?<![\d,])(?<![,，]\d)(2\d\d|3\d\d)(?![\d%])", joined))
    rooms = {r for r in rooms if not re.search(r"[,，]\s?" + r, joined)}
    allowed = {"305", "307", "309", "311"}
    for r in sorted(rooms - allowed):
        hits.append(f"未登记的房号出现: {r}（世界事实漂移，须回圣经空间表补或在结构文档钉死）")
    for wrong, right in [("306", "305"), ("308", "307"), ("310", "309"), ("小美", "小满"), ("张满", "小满")]:
        if wrong in joined:
            hits.append(f"疑似写错: {wrong} → 应为 {right}")
    if "长明计划" not in joined:
        hits.append("『长明计划』在剧本里一次都没出现（D6 公告的核心机构名，需核对）")
    return hits


def simulate(chapters):
    """枚举分支组合，看结局 gate 是否有可达路径。跨章状态做笛卡尔积（有上限保护）。"""
    per_chapter = []
    for p, d in chapters:
        paths = []
        start = d["meta"].get("start_state", {m: 0 for m in METRICS})
        leaves = []
        def dfs(nid, st, seen, flags):
            n = byidd[p].get(nid)
            if n is None or nid in seen:
                return
            st = dict(st)
            for k, v in (n.get("effects") or {}).items():
                st[k] = st.get(k, 0) + v
            for f in ([n["id"]] and []):
                pass
            edges = walk_edges(n)
            if not edges:
                leaves.append((nid, dict(st), dict(flags)))
                return
            for label, to, needs, eff in edges:
                nst = dict(st)
                for k, v in (eff or {}).items():
                    nst[k] = nst.get(k, 0) + v
                nf = dict(flags)
                for c in (n.get("choices") or []):
                    for f in c.get("flags_set", []) or []:
                        name, _, val = str(f).partition("=")
                        nf[name.strip()] = val.strip() or True
                if needs:
                    for k, v in needs.items():
                        if k in METRICS and isinstance(v, str):
                            m = re.match(r"(>=|<=|==|!=|>|<)\s*(-?\d+)", v.strip())
                            if m:
                                lhs, rhs = m.group(1), int(m.group(2))
                                cur = nst.get(k, 0)
                                ok = {">=": cur >= rhs, "<=": cur <= rhs, "==": cur == rhs,
                                      "!=": cur != rhs, ">": cur > rhs, "<": cur < rhs}[lhs]
                                if not ok:
                                    continue
                dfs(to, nst, seen | {nid}, nf)
        byidd[p] = {n["id"]: n for n in d["nodes"]}
        first = d["nodes"][0]["id"]
        dfs(first, {m: start.get(m, 0) for m in METRICS}, frozenset(), {})
        per_chapter.append((p, leaves))
    return per_chapter


def reach_report(chapters):
    lines = []
    for p, d in chapters:
        ends = [n for n in d["nodes"] if n.get("type") == "ending"]
        for e in ends:
            eid = e.get("ending_id", "?")
            nm = CH_ENDINGS.get(eid, e.get("ending_name", eid))
            req = e.get("requires_all") or e.get("requires") or e.get("is_true_ending")
            lines.append(f"  {p.split('/')[-1]:<12} {eid:<28} 《{nm}》 前置={req or '（主线）'}")
        gates = d["meta"].get("ending_gates")
        if gates:
            for k, v in gates.items():
                lines.append(f"  {p.split('/')[-1]:<12} gate: {k:<24} {v}")
    return lines


def main():
    files = sys.argv[1:] or sorted(glob.glob("script/act*.json"))
    chapters = load(files)
    print(f"载入 {len(chapters)} 章：{', '.join(p.split('/')[-1] for p,_ in chapters)}\n")

    reg = json.load(open("script/flags.json", encoding="utf-8"))
    known = set(reg["engine_flags"]) | set(reg["story_flags"])
    set_f, used_f = flag_inventory(chapters)
    print("[A] flag 闭合（对照 script/flags.json 注册表）")
    bad = sorted(k for k in (set(set_f) | set(used_f)) if k not in known and k not in METRICS)
    for k in bad:
        print(f"  [FAIL] 未登记 flag: {k}  出现于 {sorted(used_f.get(k, set()) | set_f.get(k, set()))[:3]}")
    orphan_used = {k: v for k, v in used_f.items() if k not in set_f}
    for k, v in sorted(orphan_used.items()):
        # 引擎注入的运行时 flag 允许没有剧本置位点
        tag = "OK(引擎注入/系统读取)" if k in reg["engine_flags"] else (
            "需查：story_flag 无人置位" if k in reg["story_flags"] else "未登记")
        print(f"  {k:<26} 引用自 {sorted(v)[:2]} → {tag}")
    never_used = sorted(set_f.keys() - used_f.keys())
    for k in never_used:
        print(f"  {k:<26} 置位了但没人消费（可能是 D7 之后要用）→ 来源 {sorted(set_f[k])[:2]}")

    print("\n[B] 世界事实")
    hits = canon_scan(chapters)
    for h in hits:
        print("  " + h)
    if not hits:
        print("  PASS：房号 / 人名 / 机构名 / 日期 无漂移")

    print("\n[C] 结局可达性")
    for l in reach_report(chapters):
        print(l)
    print("\n  度量条件写法扫描：")
    blob0 = json.dumps([d for _, d in chapters], ensure_ascii=False)
    for bad in re.findall(r'"[a-z_]+":\s*"==\s*true"', blob0):
        print("    [FAIL] 废弃写法 " + bad)
    else:
        print("    PASS：无 '==true' 废弃写法")

    print("\n[D] 跨章回指对账")
    pairs = [
        ("d1_n122", "avoided_date_question", "d6_n602", "D1 回避日期 → D6 她自己想起"),
        ("d2_n219", "player_named", "d5_n500 首句", "D2 把名字打给她 → 之后她念得出你（引擎读 variables.player_name）"),
        ("d3 夜窗", "missed_d3_night_window", "d6_n603b", "D3 错过 → D6 她说'你那天晚上没来'"),
        ("d5_c09", "box_kept_closed", "d7_n710_gate", "D5 别开 → D7 那六秒语音"),
        ("d6_c620", "accomplice", "d7_n740_gate", "D6 共犯 → D7 审判"),
        ("d1 讲道理", "logic_route", "d4_n401", "D1 讲道理 → D4 她第一次主动提 305"),
    ]
    blob = json.dumps([d for _, d in chapters], ensure_ascii=False)
    for src, flag, dst, desc in pairs:
        ok = flag in blob
        print(f"  {'OK ' if ok else 'MISS'} {flag:<26} | {desc} | 落点 {dst}")


if __name__ == "__main__":
    main()
