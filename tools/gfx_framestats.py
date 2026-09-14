# -*- coding: utf-8 -*-
"""
解析 `adb shell dumpsys gfxinfo <pkg> framestats` 的输出，客观测量真机帧率并归因。

用法:
    python gfx_framestats.py <gfxinfo输出文件>

输出:
    ① 平均 fps + 帧间隔直方图（间隔 8.3ms = 120Hz 一个 vsync；16.7ms = 丢了一拍）
    ② UI 线程 / 渲染线程 / GPU 各段耗时（判断瓶颈在哪一层）
    ③ 超预算帧数

判读要点（真机踩过的坑）:
    - 平均 fps 只有 ~100、但 UI/渲染/GPU 全部远低于 8.33ms 预算
      → 不是算力问题，是"帧请求晚了一个 vsync"（IntendedVsync 自己从 8.3 跳到 16.7）
      → Compose 用 withFrameNanos（挂起恢复要过调度器）会有 22% 的帧这样；
        换成 withInfiniteAnimationFrameNanos（在帧回调内部执行、回调内续订下一帧）即可
    - gfxinfo 的 "Janky frames" 阈值对比的是当前刷新间隔（120Hz = 8.33ms），
      和帧间隔直方图要一起看：直方图才是"内容真的每秒更新多少次"
    - 用 `reset` 后再采，且采样窗口里不要有别的操作（点击/切应用都会污染）
"""
import collections
import re
import sys


def load_rows(path):
    txt = open(path, encoding="utf-8", errors="ignore").read()
    blocks = re.findall(r"---PROFILEDATA---(.*?)---PROFILEDATA---", txt, re.S)
    rows = []
    for blk in blocks:
        for line in blk.strip().split("\n"):
            f = line.split(",")
            # 只收数据行（表头以 Flags 开头；数据行第 3 列 IntendedVsync 是纯数字）
            if len(f) > 23 and f[2].strip().isdigit():
                rows.append(f)
    return rows


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(len(xs) * p))]


def main():
    rows = load_rows(sys.argv[1])
    if not rows:
        print("没有解析到帧数据：确认用的是 `dumpsys gfxinfo <pkg> framestats`，且采样期间 App 在前台")
        return 1

    ts = sorted(int(r[2]) for r in rows)          # IntendedVsync (ns)
    dur = (ts[-1] - ts[0]) / 1e9
    iv = collections.Counter(round((y - x) / 1e6) for x, y in zip(ts, ts[1:]))
    print("采样 %d 帧 / %.2f s → 平均 %.1f fps" % (len(ts), dur, len(ts) / dur))
    print("帧间隔(ms):次数 ->", sorted(iv.items()))

    ui, rt, gpu = [], [], []
    for r in rows:
        try:
            pt, sq, ss, iss, sb, gpu_done = (int(r[7]), int(r[13]), int(r[14]),
                                             int(r[15]), int(r[16]), int(r[20]))
        except (ValueError, IndexError):
            continue
        ui.append((sq - pt) / 1e6)     # 组合+记录 draw command
        rt.append((sb - ss) / 1e6)     # 渲染线程同步+绘制
        gpu.append((gpu_done - iss) / 1e6)
    if ui:
        print("UI线程 中位 %.2f 90%% %.2f 最大 %.2f | 渲染 中位 %.2f | GPU 中位 %.2f 最大 %.2f"
              % (pct(ui, .5), pct(ui, .9), max(ui), pct(rt, .5), pct(gpu, .5), max(gpu)))
        print("GPU 超 8.33ms 帧数: %d / %d" % (sum(1 for x in gpu if x > 8.33), len(gpu)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
