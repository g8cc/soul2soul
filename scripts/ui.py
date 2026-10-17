#!/usr/bin/env python3
"""UI 自动化助手：从 uiautomator dump 里找到指定文本节点的中心坐标"""
import re
import os
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET


def _find_adb():
    """Resolve adb once, while allowing CI/Linux/other macOS SDK layouts."""
    configured = os.environ.get("ADB_BIN")
    if configured:
        return configured
    found = shutil.which("adb")
    if found:
        return found
    candidates = (
        os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),
        os.path.expanduser("~/Android/Sdk/platform-tools/adb"),
    )
    for candidate in candidates:
        if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
            return candidate
    return "adb"


ADB = _find_adb()


def dump(ser):
    """uiautomator dump 在高负载下会失败/空输出。
    必须拿到有效 XML；失败重试并降级到 exec-out 直读，绝不静默返回空层级
    （否则上层把"没 dump 到"当成"目标已消失"造成假成功）。"""
    import time
    last = ""
    for attempt in range(12):
        try:
            dumped = subprocess.run(
                [ADB, "-s", ser, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
                capture_output=True, text=True, timeout=30,
            )
            xml_result = subprocess.run(
                [ADB, "-s", ser, "shell", "cat", "/sdcard/ui.xml"],
                capture_output=True, text=True, timeout=30,
            )
        except (OSError, subprocess.SubprocessError) as exc:
            last = str(exc)
            time.sleep(2)
            continue
        xml = xml_result.stdout
        if dumped.returncode == 0 and xml.strip().startswith("<?xml"):
            last = xml
            if "responding" in xml:
                tapped = False
                try:
                    for el in ET.fromstring(xml).iter("node"):
                        if el.get("text") == "Wait":
                            x, y = center(el.get("bounds"))
                            subprocess.run(
                                [ADB, "-s", ser, "shell", "input", "tap", str(x), str(y)],
                                capture_output=True, timeout=10,
                            )
                            tapped = True
                            break
                except (ET.ParseError, subprocess.SubprocessError):
                    tapped = False
                if tapped:
                    time.sleep(2)
                    continue
                return xml
            return xml
        # 降级: 直接从 stdout 读
        try:
            xml = subprocess.run(
                [ADB, "-s", ser, "exec-out", "uiautomator", "dump", "/dev/tty"],
                capture_output=True, text=True, timeout=30,
            ).stdout
        except (OSError, subprocess.SubprocessError) as exc:
            last = str(exc)
            xml = ""
        if xml.strip().startswith("<?xml"):
            return xml
        time.sleep(2)
    raise RuntimeError(f"uiautomator dump failed on {ser}: {last[:80]!r}")


def find_nodes(ser, pattern):
    """对外契约不变：[(text, bounds), …]（list/code 命令按二元组解包）"""
    return [(t, b) for t, b, _ in _match(ser, pattern)]


def _match(ser, pattern):
    xml = dump(ser)
    nodes = []
    root = ET.fromstring(xml)
    for el in root.iter("node"):
        text = el.get("text") or ""
        desc = el.get("content-desc") or ""
        if re.search(pattern, text) or re.search(pattern, desc):
            nodes.append((text or desc, el.get("bounds"), el.get("clickable") == "true"))
    return nodes


def _match_one(ser, pattern):
    """返回 (bounds, is_clickable)，无命中则 None。命中多节点时优先 clickable=true：
    uiautomator 按文档顺序返回，而说明文字永远排在按钮前面——
    「TA 想和你一起玩手机，接受吗？」里含「接受」、「Start recording or casting…」里含
    「Start recording」，实测 tap 因此连续几十秒点在正文上：来电 30s 自动拒接、投屏授权永不落地。"""
    nodes = _match(ser, pattern)
    if not nodes:
        return None
    for text, bounds, clickable in nodes:
        if clickable:
            return bounds, True
    return nodes[0][1], False


def center(bounds):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not m:
        raise ValueError(f"invalid UI bounds: {bounds!r}")
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap_text(ser, pattern, verify=True):
    """点击后校验目标是否消失（模拟器 UI 线程繁忙时 input tap 会被吞，重试直到生效）。
    目标从未出现 → NOT_FOUND（不视为成功）。
    verify 的口径必须和点击口径一致：点的是 clickable 节点，消失判据也只能看 clickable 节点，
    否则正文里含同一个词（「TA 想和你一起玩手机，接受吗？」含「接受」）会让判据永远不成立、
    白重试 5 轮（每轮 2 次 dump ≈ 一二十秒），来电 30s 窗口直接吃光。
    verify=False（CLI 传 `once`）给「选完还在」的目标用：单选钮（整个屏幕）点完弹窗仍在，
    它是否真生效由上层业务判据（如 invite sent）来证明。"""
    import time
    seen = False
    for _ in range(5):
        hit = _match_one(ser, pattern)
        if hit is None:
            if seen:
                return True  # 之前的点击已生效
            time.sleep(1.5)
            continue
        bounds, clickable = hit
        seen = True
        x, y = center(bounds)
        time.sleep(1.2)
        subprocess.run([ADB, "-s", ser, "shell", "input", "tap", str(x), str(y)])
        time.sleep(2.5)
        if not verify:
            return True
        if clickable:
            if not [b for _, _, c in _match(ser, pattern) if c]:
                return True
        elif not find_nodes(ser, pattern):
            return True
    return False


def has_text(ser, pattern):
    return len(find_nodes(ser, pattern)) > 0


def read_code(ser):
    """读取 6 位配对码（tvMyCode 的 text）"""
    nodes = find_nodes(ser, r"^\d{6}$")
    return nodes[0][0] if nodes else None


if __name__ == "__main__":
    try:
        cmd, ser = sys.argv[1], sys.argv[2]
        pat = sys.argv[3] if len(sys.argv) > 3 else ""
        if cmd == "tap":
            # 第 4 个参数 once → 只点不校验（目标点完仍在界面上的，如投屏两步弹窗的单选钮）
            ok = tap_text(ser, pat, verify=(len(sys.argv) < 5 or sys.argv[4] != "once"))
            print("TAPPED" if ok else "NOT_FOUND")
        elif cmd == "has":
            # 契约：只打印 YES/NO 且恒返回 0（大量调用点写成 `$(ui has ..) || echo NO`，
            # 若在此返回非 0 会多打一行，把比较变成 "NO\nNO"）。断言一律比字符串。
            print("YES" if has_text(ser, pat) else "NO")
        elif cmd == "code":
            print(read_code(ser) or "NONE")
        elif cmd == "list":
            for t, b in find_nodes(ser, pat or "."):
                print(t, b)
    except Exception as e:
        print(f"ERROR: {e}")
        sys.exit(3)
