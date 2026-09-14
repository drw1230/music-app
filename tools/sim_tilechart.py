# -*- coding: utf-8 -*-
"""
离线复刻 Kotlin 的 buildTiles() 生成规则，用手机里拉出来的**真实谱面**验证长按块/双押块的数量与节奏。

用法:
    python sim_tilechart.py chart_*.json

为什么要有这个脚本:
    "长按块不出现 / 双押块刷屏" 这类问题在真机上要玩很久才看得出来。
    在电脑上用同一份谱面 + 同一套 Java Random 复刻一遍，几秒钟就能数清楚，
    而且能顺手检查长按是否重叠、双押是否成对、节奏是否合理。
"""
import json
import sys


class JRandom:
    """java.util.Random 的忠实复刻（Kotlin 里用的就是它，同种子必然同结果）"""

    def __init__(self, seed):
        self.seed = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)

    def _next(self, bits):
        self.seed = (self.seed * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
        return self.seed >> (48 - bits)

    def next_int(self, bound):
        if (bound & -bound) == bound:
            return (bound * self._next(31)) >> 31
        while True:
            bits = self._next(31)
            val = bits % bound
            if bits - val + (bound - 1) < (1 << 31):
                return val

    def next_boolean(self):
        return self._next(1) != 0

    def next_float(self):
        return self._next(24) / float(1 << 24)


HOLD_MIN_GAP_MS = 1400
HOLD_MIN_MS = 900
HOLD_MAX_MS = 2200
HOLD_SPACING_MS = 15000
DOUBLE_GAP_MS = 520
DOUBLE_SPACING_MS = 9000
DOUBLE_CHANCE = 0.55


def double_lane(lane, rnd):
    far = [l for l in range(4) if abs(l - lane) >= 2]
    if far:
        return far[rnd.next_int(len(far))]
    return [l for l in range(4) if l != lane][0]


def build_tiles(duration_ms, events):
    peaks = sorted((e for e in events if e["ty"] in (0, 2)), key=lambda e: e["t"])
    if not peaks:
        return []
    rnd = JRandom(duration_ms * 7 + len(peaks) * 31)
    out = []
    last_lane, direction, last_t = 1, 1, -10000
    last_hold_at, last_double_at = -1_000_000, -1_000_000
    for i, e in enumerate(peaks):
        gap = e["t"] - last_t
        if gap < 350:
            lane = last_lane + direction
            if lane not in range(4):
                direction = -direction
                lane = last_lane + direction
            if lane not in range(4):
                lane = [l for l in range(4) if l != last_lane][0]
        else:
            direction = 1 if rnd.next_boolean() else -1
            lane = rnd.next_int(4)
            if lane == last_lane:
                lane = (lane + 1 + rnd.next_int(3)) % 4
        gap_next = peaks[i + 1]["t"] - e["t"] if i + 1 < len(peaks) else float("inf")

        hold_ms = 0
        if HOLD_MIN_GAP_MS <= gap_next <= HOLD_MIN_GAP_MS * 8 and e["t"] - last_hold_at >= HOLD_SPACING_MS:
            last_hold_at = e["t"]
            hold_ms = min(max(gap_next - 500, HOLD_MIN_MS), HOLD_MAX_MS)

        as_double = (hold_ms == 0 and gap >= DOUBLE_GAP_MS and gap_next >= DOUBLE_GAP_MS
                     and e["t"] - last_double_at >= DOUBLE_SPACING_MS
                     and rnd.next_float() < DOUBLE_CHANCE)
        if as_double:
            lane2 = double_lane(lane, rnd)
            out.append({"t": e["t"], "lane": lane, "hold": 0, "pair": lane2})
            out.append({"t": e["t"], "lane": lane2, "hold": 0, "pair": lane})
            last_double_at = e["t"]
        else:
            out.append({"t": e["t"], "lane": lane, "hold": hold_ms, "pair": -1})
        last_lane, last_t = lane, e["t"]
    return out


def report(path):
    d = json.load(open(path, encoding="utf-8"))
    dur, events = d["d"], d["v"]
    tiles = build_tiles(dur, events)
    holds = [t for t in tiles if t["hold"] > 0]
    pairs = sorted({(t["t"], min(t["lane"], t["pair"])) for t in tiles if t["pair"] >= 0})
    normals = [t for t in tiles if t["hold"] == 0 and t["pair"] < 0]
    print("── %s" % path)
    print("   时长 %.0f s | 原始事件 %d → 方块 %d（普通 %d / 长按 %d / 双押 %d 对）"
          % (dur / 1000, len(events), len(tiles), len(normals), len(holds), len(pairs)))

    # 长按块：时长分布 + 是否与前一块重叠（重叠会导致同轨冲突）
    if holds:
        lens = sorted(h["hold"] for h in holds)
        print("   长按时长 ms：%s（中位 %d）" % (lens, lens[len(lens) // 2]))
        print("   长按出现时刻(s)：%s" % [round(h["t"] / 1000) for h in holds])
        # 与下一块的间隔（应 ≥ 500ms，否则按着按着下一块就来了）
        nxt = []
        for h in holds:
            later = [t["t"] for t in tiles if t["t"] > h["t"] + h["hold"]]
            nxt.append(min(later) - (h["t"] + h["hold"]) if later else -1)
        print("   长按结束→下一块间隔 ms：%s（要求 ≥ 400）" % nxt)
    else:
        print("   ⚠️ 没有生成任何长按块（检查 HOLD_MIN_GAP_MS 是否过大）")

    if pairs:
        gaps = [round((b - a) / 1000, 1) for a, b in zip([p[0] for p in pairs], [p[0] for p in pairs][1:])]
        print("   双押出现时刻(s)：%s" % [round(p[0] / 1000) for p in pairs])
        print("   双押间隔(s)：%s（要求 ≥ 9）" % gaps)
    else:
        print("   ⚠️ 没有生成任何双押块（检查 DOUBLE_GAP_MS / DOUBLE_CHANCE）")

    # 经典模式：只取前 50 块时的构成
    c50 = tiles[:50]
    print("   经典前 50 块：普通 %d / 长按 %d / 双押 %d 对"
          % (len([t for t in c50 if t["hold"] == 0 and t["pair"] < 0]),
             len([t for t in c50 if t["hold"] > 0]),
             len({(t["t"], min(t["lane"], t["pair"])) for t in c50 if t["pair"] >= 0})))
    # 不可玩性检查：同一条轨道上是否有时间重叠的两个块
    by_lane = {}
    for t in tiles:
        by_lane.setdefault(t["lane"], []).append(t)
    bad = 0
    for lane, ts in by_lane.items():
        ts.sort(key=lambda x: x["t"])
        for a, b in zip(ts, ts[1:]):
            if b["t"] - a["t"] < 400:
                bad += 1
    print("   ⚠️ 同轨过近（<400ms）的块对数：%d" % bad)


if __name__ == "__main__":
    for p in sys.argv[1:]:
        report(p)
        print()
