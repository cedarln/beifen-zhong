#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把剧本 JSON 渲染成可通读的对话实录（Markdown）

它同时是一台'行走机'：从首节点出发沿 next / choices 走完一章，
走不到的节点会被列为孤儿——这比任何静态检查都能更早暴露断链。

用法：
    python3 tools/render_transcript.py script/act1.json > docs/03-第一章-对话实录.md
"""
import json
import sys
import collections

SPEAKER = {"xiaoman": "小满", "you": "你", "system": "系统"}
WAIT_UNITS = {"s": "秒", "m": "分", "h": "小时"}


CN = "零一二三四五六七八九十"


def cn_num(i):
    i = int(i or 0)
    return CN[i] if i < 10 else f"{i}"


def dur(ms):
    s = int(ms) // 1000
    if s < 60:
        return f"{s} 秒"
    m, sec = divmod(s, 60)
    if m < 60:
        return f"{m} 分{sec} 秒" if sec else f"{m} 分钟"
    h, m = divmod(m, 60)
    return f"{h} 小时{m} 分" if m else f"{h} 小时"


def bubbles(n):
    t = n.get("text") or []
    return [t] if isinstance(t, str) else list(t)


def render(path, out=sys.stdout):
    d = json.load(open(path, encoding="utf-8"))
    meta = d["meta"]
    nodes = d["nodes"]
    by = collections.OrderedDict((n["id"], n) for n in nodes)
    order = list(by)

    out.write(f"# 第{cn_num(meta.get('chapter'))}章 · {meta.get('title', '')}｜对话实录\n\n")
    out.write(f"> 本页由 `tools/render_transcript.py {path}` 从 JSON 生成，不要手工编辑；"
              f"改文本请改 `script/`，改完重跑校验。\n\n")
    out.write(f"> 项目 {meta.get('project')} · 版本 {meta.get('version')} · "
              f"主动阅读约 {meta.get('reading_minutes')} 分钟 · 真实等待 {meta.get('real_wait_total')}\n\n")
    st = meta.get("start_state", {})
    out.write(f"开场度量：信任 {st.get('trust')} · 感知 {st.get('awareness')} · 完好度 {st.get('integrity')}\n\n")
    out.write("---\n\n")

    visited = set()
    # ui_entry 节点是引擎从界面上直接触发的（如会话页右上角『结束通话』），它也是入口
    queue = [order[0]] + [n["id"] for n in nodes if n.get("ui_entry")]
    trace = []
    while queue:
        nid = queue.pop(0)
        n = by.get(nid)
        if n is None or nid in visited:
            continue
        visited.add(nid)
        trace.append(n)

        t = n["type"]
        if t == "wait":
            out.write(f"**〔等待 {dur(n.get('duration_ms', 0))}〕** 她在做：{n.get('activity', '—')}\n\n")
            fl = n.get("first_line_after_wait")
            if fl:
                out.write(f"回来后第一句（小满）：{fl}\n\n")
        elif t == "silence":
            out.write(f"**〔静默 {dur(n.get('duration_ms', 0))}〕**\n\n")
        elif t == "choices":
            out.write(f"**〔选择 · 出自{SPEAKER.get(n.get('prompt_from', 'xiaoman'), '小满')}〕**\n\n")
            for i, c in enumerate(n.get("choices", []) or [], 1):
                eff = " ".join(f"{ {'trust': '信任', 'awareness': '感知', 'integrity': '完好度'}[k] }{v:+d}"
                               for k, v in (c.get("effects") or {}).items())
                needs = f"（需 {c['needs']}）" if c.get("needs") else ""
                out.write(f"{i}. {c.get('text', '')} → `{c.get('to')}` {eff}{needs}\n")
            out.write("\n")
        elif t == "letter":
            who = SPEAKER.get(n.get("speaker"), "小满")
            out.write(f"**〔灭屏信 · {who}〕**\n\n")
            for line in bubbles(n):
                out.write(f"> {line}\n>\n")
            out.write("\n")
        elif t == "ending":
            out.write(f"**〔结局 · {n.get('ending_name', n.get('ending_id'))}〕**\n\n")
            for line in bubbles(n):
                out.write(f"{line}\n\n")
            if n.get("after_action"):
                out.write(f"*之后：{n['after_action']}*\n\n")
        else:
            who = SPEAKER.get(n.get("speaker"), n.get("speaker"))
            sig = f"  `⟨{n['signal']}⟩`" if n.get("signal") else ""
            for i, line in enumerate(bubbles(n)):
                out.write(f"**{who if i == 0 else '　'}**　{line}{sig if i == 0 else ''}\n\n")
            for cl in ([n["conditional_line"]] if isinstance(n.get("conditional_line"), dict) else []) + \
                      (n.get("conditional_lines") or []):
                cond, extra = cl.get("if"), None
                if cl.get("append_text"):
                    extra = "追加：" + "".join(cl["append_text"])
                elif cl.get("replace_text"):
                    extra = "整段替换：" + "".join(cl["replace_text"])
                out.write(f"*〔条件 {cond} → {extra}〕*\n\n")
            if n.get("needs"):
                out.write(f"*〔闸门 {n['needs']}，否则 → `{n.get('fallback_id')}`〕*\n\n")
            if n.get("effects"):
                out.write(f"*〔Δ {n['effects']}〕*\n\n")

        for e in ("next", "fallback_id", "then", "after"):
            if n.get(e):
                queue.append(n[e])
        for c in n.get("choices", []) or []:
            if c.get("to"):
                queue.append(c["to"])
        for k in ("on_grant", "on_deny"):
            if isinstance(n.get(k), dict) and n[k].get("then"):
                queue.append(n[k]["then"])

    unreached = [i for i in order if i not in visited]
    out.write("---\n\n")
    out.write(f"## 行走结果\n\n- 走完 {len(visited)} / {len(order)} 个节点\n")
    if unreached:
        out.write(f"- **走不到（多半是孤儿，须接边）**：{', '.join('`' + u + '`' for u in unreached)}\n")
    else:
        out.write("- 无孤儿节点：本章从开场到结局连通\n")
    out.write("- 变量 `{{device_date}}` / `{{player_name}}` / `{{tomorrow_weekday_cn}}` 由引擎在渲染时替换\n")


if __name__ == "__main__":
    render(sys.argv[1] if len(sys.argv) > 1 else "script/act1.json")
