#!/usr/bin/env python3
"""UI 自动化助手：从 uiautomator dump 里找到指定文本节点的中心坐标"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ADB = "/Users/wardonguo/Library/Android/sdk/platform-tools/adb"


def dump(ser):
    """uiautomator dump 在高负载下会失败/空输出。
    必须拿到有效 XML；失败重试并降级到 exec-out 直读，绝不静默返回空层级
    （否则上层把"没 dump 到"当成"目标已消失"造成假成功）。"""
    import time
    last = ""
    for attempt in range(12):
        subprocess.run([ADB, "-s", ser, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
                       capture_output=True, timeout=30)
        xml = subprocess.run([ADB, "-s", ser, "shell", "cat", "/sdcard/ui.xml"],
                             capture_output=True, text=True, timeout=30).stdout
        if xml.strip().startswith("<?xml"):
            last = xml
            if "responding" in xml:
                tapped = False
                for el in ET.fromstring(xml).iter("node"):
                    if el.get("text") == "Wait":
                        x, y = center(el.get("bounds"))
                        subprocess.run([ADB, "-s", ser, "shell", "input", "tap", str(x), str(y)],
                                       capture_output=True)
                        tapped = True
                        break
                if tapped:
                    time.sleep(2)
                    continue
                return xml
            return xml
        # 降级: 直接从 stdout 读
        xml = subprocess.run([ADB, "-s", ser, "exec-out", "uiautomator", "dump", "/dev/tty", "2>/dev/null"],
                             capture_output=True, text=True, timeout=30).stdout
        if xml.strip().startswith("<?xml"):
            return xml
        time.sleep(2)
    raise RuntimeError(f"uiautomator dump failed on {ser}: {last[:80]!r}")


def find_nodes(ser, pattern):
    xml = dump(ser)
    nodes = []
    root = ET.fromstring(xml)
    for el in root.iter("node"):
        text = el.get("text") or ""
        desc = el.get("content-desc") or ""
        if re.search(pattern, text) or re.search(pattern, desc):
            nodes.append((text or desc, el.get("bounds")))
    return nodes


def center(bounds):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap_text(ser, pattern, verify=True):
    """点击后校验目标是否消失（模拟器 UI 线程繁忙时 input tap 会被吞，重试直到生效）。
    目标从未出现 → NOT_FOUND（不视为成功）。"""
    import time
    seen = False
    for _ in range(5):
        nodes = find_nodes(ser, pattern)
        if not nodes:
            if seen:
                return True  # 之前的点击已生效
            time.sleep(1.5)
            continue
        seen = True
        x, y = center(nodes[0][1])
        time.sleep(1.2)
        subprocess.run([ADB, "-s", ser, "shell", "input", "tap", str(x), str(y)])
        time.sleep(2.5)
        if not verify:
            return True
        if not find_nodes(ser, pattern):
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
            ok = tap_text(ser, pat)
            print("TAPPED" if ok else "NOT_FOUND")
        elif cmd == "has":
            print("YES" if has_text(ser, pat) else "NO")
        elif cmd == "code":
            print(read_code(ser) or "NONE")
        elif cmd == "list":
            for t, b in find_nodes(ser, pat or "."):
                print(t, b)
    except Exception as e:
        print(f"ERROR: {e}")
        sys.exit(3)
