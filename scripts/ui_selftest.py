#!/usr/bin/env python3
"""scripts/ui.py 的 tap 校验路径自检（t0 调用，不需要设备）。

为什么单独一个文件而不是 grep 锚点：`ui tap` 的"点完确认目标消失、没消失就重试"是 t4
防 `input tap 被吞` 的唯一机制，而它坏起来**没有任何静态形状可看**——10-19 实测到一段
列表推导里引用了没绑定的名字，运行时抛 NameError，e2e 里每条 `ui tap … || true`
都把非零退出吞掉，于是整套真机断言退化成"点一次、不校验"，绿得和以前一模一样。
只有真的把这条路径跑一遍才能区分"在保护"和"已经废了"。

打桩的返回形状（写错会让用例自己先炸，别当成 ui.py 坏了）：
`_match` → `[(text, bounds, clickable)]`，`_match_one` → `(bounds, clickable)` 或 None，
`find_nodes` → `[(text, bounds)]`。
"""
import os
import sys
import time
import types

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui  # noqa: E402

B = "[0,0][10,10]"
B2 = "[20,20][30,30]"

taps = []


class FakeProc:
    returncode = 0
    stdout = ""


def fake_run(cmd, *a, **kw):
    taps.append(cmd)
    return FakeProc()


def scripted(values, default):
    """按调用次序返回值；超出预设次数后固定返回 default（模拟稳定状态）"""
    seq = list(values)
    state = {"i": 0}

    def fn(_ser, _pat):
        i = state["i"]
        state["i"] = i + 1
        return seq[i] if i < len(seq) else default
    return fn


def run(case, one_seq, one_default, match_seq, match_default, node_seq, node_default, expect):
    taps.clear()
    ui._match_one = scripted(one_seq, one_default)
    ui._match = scripted(match_seq, match_default)
    ui.find_nodes = scripted(node_seq, node_default)
    ui.subprocess = types.SimpleNamespace(run=fake_run)
    time.sleep = lambda _s: None
    got = ui.tap_text("fake-serial", "结束")
    if got != expect:
        print(f"FAIL {case}: 返回 {got}，期望 {expect}（点了 {len(taps)} 次）")
        return False
    return True


def main():
    ok = True
    # 1 正常：亮着可点节点 → 点一次 → 它消失了 ⇒ TAPPED
    ok &= run("点完即消失",
              one_seq=[(B, True)], one_default=None,
              match_seq=[[]], match_default=[],
              node_seq=[[]], node_default=[],
              expect=True)
    if len(taps) != 1:
        print(f"FAIL 点完即消失: 应当只点一次，实际 {len(taps)} 次")
        ok = False
    # 2 被吞：点完它还在（且仍可点）⇒ 必须继续重试直到消失；
    #   这一条就是 10-19 崩掉的那段——校验用 `_match` 只挑 clickable，
    #   列表非空才会真的走到那段推导里去（空列表会让 NameError 隐身，所以必须给它一条真节点）
    ok &= run("第一下被吞要重试",
              one_seq=[(B, True), (B, True), None], one_default=None,
              match_seq=[[("结束", B, True), ("结束", B2, False)]], match_default=[],
              node_seq=[[]], node_default=[],
              expect=True)
    if len(taps) != 2:
        print(f"FAIL 第一下被吞要重试: 应当点两次，实际 {len(taps)} 次")
        ok = False
    # 3 目标从来没出现：不许算成功（NOT_FOUND），且一次都不该点
    ok &= run("目标不存在",
              one_seq=[], one_default=None,
              match_seq=[], match_default=[],
              node_seq=[], node_default=[],
              expect=False)
    if len(taps) != 0:
        print(f"FAIL 目标不存在: 不该点任何地方，实际 {len(taps)} 次")
        ok = False
    print("ui_selftest PASS" if ok else "ui_selftest FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
