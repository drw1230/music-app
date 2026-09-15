#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
音源可用性探测 —— 排查"QQ音乐/酷狗被锁"到底锁在哪一环。

三平台各测两环：
  ① 搜索接口（能不能搜到歌 → 拿到 id/hash）
  ② 取流接口（能不能换到可播放的 URL）
每环都打印 HTTP 码 + 关键字段，一眼看出断点。

用法:
    python tools/probe_sources.py            # 默认测「周杰伦 晴天」
    python tools/probe_sources.py 五月天 温柔
"""
import json
import sys
import urllib.parse
import urllib.request

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"
UA_M = ("Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 "
        "Chrome/120 Mobile Safari/537.36")

# 网易云匿名 cookie（App 里用的那套）
NETEASE_COOKIE = "os=pc; appver=2.10.6; MUSIC_A=" + "0" * 32


def get(url, headers=None, timeout=12, rng=None):
    """返回 (http_code, body_text)。不抛异常，失败返回 (None, 错误字符串)。rng 用于 Range 探测。"""
    h = dict(headers or {"User-Agent": UA})
    if rng:
        h["Range"] = rng
    req = urllib.request.Request(url, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return None, f"{type(e).__name__}: {e}"


def jget(url, headers=None):
    code, body = get(url, headers)
    try:
        return code, json.loads(body)
    except Exception:
        return code, None


def line(t):
    print("\n" + "=" * 68)
    print(t)
    print("=" * 68)


def probe_kugou(title, artist):
    line("① 酷狗 KUGOU")
    kw = urllib.parse.quote(f"{title} {artist}")
    code, j = jget(f"https://songsearch.kugou.com/song_search_v2?keyword={kw}&page=1&pagesize=1")
    print(f"[搜索] HTTP {code}")
    if not j or j.get("status") != 1:
        print("  ❌ 搜索失败/被拦:", str(j)[:200])
        return
    lst = (j.get("data") or {}).get("lists") or []
    if not lst:
        print("  ❌ 搜索结果为空")
        return
    s = lst[0]
    print(f"  ✅ 搜到: {s.get('SongName')} / {s.get('SingerName')}")
    print(f"     FileHash={s.get('FileHash')}  HQFileHash={s.get('HQFileHash')}  SQFileHash={s.get('SQFileHash')}")
    print(f"     FileSize={s.get('FileSize')} HQFileSize={s.get('HQFileSize')} SQFileSize={s.get('SQFileSize')}")
    print(f"     Privilege: 普通={s.get('Privilege')} HQ={s.get('HQPrivilege')} "
          f"SQ={s.get('SQPrivilege')}  (0=免费可下)")
    # 时长 / 片段判定：FileSize 明显小于「时长 × 码率/8」→ 极可能是试听片段
    dur = int(s.get("Duration") or 0)
    fsz = int(s.get("FileSize") or 0)
    if dur > 0 and fsz > 0:
        expect_128 = dur * 128000 / 8
        ratio = fsz / expect_128
        verdict = ("⚠️ 疑似试听片段/低码率" if ratio < 0.75 else "✅ 体积与全景时长相符")
        print(f"     Duration={dur}s  按128kbps应有 {expect_128/1024/1024:.2f}MB，"
              f"实有 {fsz/1024/1024:.2f}MB → 比值 {ratio:.2f}  {verdict}")
        print(f"     ExtName={s.get('ExtName')}  AlbumID={s.get('AlbumID')}  "
              f"HQCover 有无={bool(s.get('HQFileHash'))}")

    for label, h in (("标准", s.get("FileHash")), ("高品", s.get("HQFileHash")), ("无损", s.get("SQFileHash"))):
        if not h:
            print(f"[取流:{label}] 跳过（无 hash）")
            continue
        code, j = jget(f"https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash={h}",
                       {"User-Agent": UA_M, "Referer": "https://www.kugou.com"})
        if j is None:
            print(f"[取流:{label}] HTTP {code} ❌ 非 JSON: {str(j)[:120]}")
            continue
        url = j.get("url")
        print(f"[取流:{label}] HTTP {code} status={j.get('status')} errcode={j.get('errcode')} "
              f"error={j.get('error')!r}")
        if url:
            print(f"  ✅ 拿到 URL: {url[:110]}")
            # 再嗅一下真能出音频流吗
            hc, body = get(url, {"User-Agent": UA_M, "Referer": "https://www.kugou.com"})
            head = body[:4]
            print(f"  音频流探测: HTTP {hc} 前4字节={head!r} "
                  f"{'✅ 是音频' if head[:3] == 'ID3' or head[:4] == 'fLaC' else '⚠️ 需人工确认'}")
        else:
            print(f"  ❌ 无 url（被锁/需登录）  privilege={j.get('privilege')} "
                  f"extName={j.get('extName')}")


def probe_qq(title, artist):
    line("② QQ 音乐")
    kw = urllib.parse.quote(f"{title} {artist}")
    hdr = {"User-Agent": UA, "Referer": "https://y.qq.com/"}
    code, j = jget(f"https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=1&w={kw}&format=json", hdr)
    print(f"[搜索] HTTP {code}")
    mid = None
    if j and (j.get("data") or {}).get("song", {}).get("list"):
        s = j["data"]["song"]["list"][0]
        mid = s.get("songmid")
        print(f"  ✅ 搜到: {s.get('songname')} / {s.get('singer', [{}])[0].get('name')}  songmid={mid}")
        print(f"     pay: {s.get('pay', {})}")
    else:
        print("  ❌ 搜索失败/被拦:", str(j)[:200])
        return
    for prefix, qn in (("M500", "标准"), ("M800", "高品"), ("F000", "无损")):
        param = {"req_0": {"module": "vkey.GetVkeyServer", "method": "CgiGetVkey",
                           "param": {"guid": "1234567890", "songmid": [mid], "songtype": [0],
                                     "uin": "0", "loginflag": 1, "platform": "20",
                                     "filename": [f"{prefix}{mid}.{'flac' if prefix == 'F000' else 'mp3'}"]}}}
        u = ("https://u.y.qq.com/cgi-bin/musicu.fcg?format=json&data="
             + urllib.parse.quote(json.dumps(param)))
        code, j2 = jget(u, hdr)
        info = (((j2 or {}).get("req_0") or {}).get("data") or {})
        purl = (info.get("midurlinfo") or [{}])[0].get("purl", "")
        sip = (info.get("sip") or [""])[0]
        print(f"[取流:{qn}] HTTP {code} purl={'✅ 有' if purl else '❌ 空'} "
              f"sip={'✅' if sip else '❌'}")
        if purl:
            full = sip + purl if sip.endswith("/") else sip + "/" + purl
            print(f"  URL: {full[:110]}")
        else:
            print(f"  ⚠️ purl 为空 = 该品质需会员/未登录  返回体: {str(info)[:160]}")


def probe_netease(title, artist):
    line("③ 网易云音乐")
    # 用搜索拿 id
    kw = urllib.parse.quote(f"{title} {artist}")
    hdr = {"User-Agent": UA, "Referer": "https://music.163.com"}
    code, j = jget("https://music.163.com/api/search/get?s=" + kw + "&type=1&limit=1", hdr)
    print(f"[搜索] HTTP {code}")
    sid = None
    if j and (j.get("result") or {}).get("songs"):
        s = j["result"]["songs"][0]
        sid = s.get("id")
        print(f"  ✅ 搜到: {s.get('name')} / {(s.get('artists') or [{}])[0].get('name')}  id={sid}")
        print(f"     fee={s.get('fee')}  (1=VIP/付费)")
    else:
        print("  ❌ 搜索失败:", str(j)[:200])
        return
    for br, qn in ((320000, "高品"), (128000, "标准")):
        for use_cookie in (False, True):
            h = dict(hdr)
            if use_cookie:
                h["Cookie"] = NETEASE_COOKIE
            code, j2 = jget(f"https://music.163.com/api/song/enhance/player/url?id={sid}"
                            f"&ids=%5B{sid}%5D&br={br}", h)
            d = ((j2 or {}).get("data") or [{}])[0]
            tag = "带匿名Cookie" if use_cookie else "裸请求"
            ok = "✅ 有 url" if d.get("url") else f"❌ url=null (code={d.get('code')})"
            print(f"[取流:{qn}/{tag}] HTTP {code} {ok} fee={d.get('fee')} br={d.get('br')}")


def scan_kugou(songs):
    """批量扫酷狗搜索，看 HQ/SQ hash 是不是'全面消失'（判断是平台改版还是单曲限制）"""
    line("酷狗批量扫描：HQ/SQ 是否全面消失")
    print(f"{'歌曲':<24} {'普通hash':<10} {'HQ':<6} {'SQ':<6} {'时长':<7} {'体积MB':<8} 判定")
    print("-" * 88)
    for title, artist in songs:
        kw = urllib.parse.quote(f"{title} {artist}")
        code, j = jget(f"https://songsearch.kugou.com/song_search_v2?keyword={kw}&page=1&pagesize=1")
        lst = ((j or {}).get("data") or {}).get("lists") or []
        if not lst:
            print(f"{title[:22]:<24} {'—':<10} {'—':<6} {'—':<6} {'—':<7} {'—':<8} ❌ 无结果")
            continue
        s = lst[0]
        hq, sq = bool(s.get("HQFileHash")), bool(s.get("SQFileHash"))
        dur = int(s.get("Duration") or 0)
        fsz = int(s.get("FileSize") or 0)
        ratio = (fsz / (dur * 128000 / 8)) if dur and fsz else 0
        verdict = "⚠️ 试听/低码" if 0 < ratio < 0.75 else ("✅ 完整" if ratio else "—")
        print(f"{title[:22]:<24} {'有' if s.get('FileHash') else '无':<10} "
              f"{'有' if hq else '无':<6} {'有' if sq else '无':<6} {dur:<7} {fsz/1024/1024:<8.2f} {verdict}")
    print("\n判读: 全部'普通有/HQ无/SQ无' → 平台收回了高品质 hash（改版），不是单曲限制")


def probe_open_sources():
    """复刻 Kotlin 侧 searchAudius / searchCcMixter 的解析逻辑，验证字段路径与取流。
    字段名写错在这里就能当场暴露，不用等装机（同 sim_tilechart.py 的思路）。"""
    line("④ 开放音源：Audius / ccMixter（复刻 App 解析逻辑）")

    # ---- Audius ----
    print("── Audius ──")
    code, j = jget("https://api.audius.co/v1/tracks/search?query=lofi&limit=5&app_name=DDmusic")
    print(f"[搜索] HTTP {code}")
    tracks = (j or {}).get("data") or []
    streamable = []
    for t in tracks:
        ok = t.get("is_streamable")
        art = ((t.get("artwork") or {}).get("480x480")
               or (t.get("artwork") or {}).get("150x150"))
        print(f"  track_id={t.get('track_id')} streamable={ok} "
              f"title={t.get('title','')[:26]!r} artist={(t.get('user') or {}).get('name','')[:18]!r} "
              f"dur={t.get('duration')}s art={'有' if art else '无'}")
        if ok:
            streamable.append(t)
    print(f"  → 可直接播的 {len(streamable)}/{len(tracks)} 首（Kotlin 用 is_streamable 过滤）")

    if streamable:
        tid = streamable[0]["track_id"]
        code, j2 = jget(f"https://api.audius.co/v1/tracks/{tid}/stream"
                        f"?app_name=DDmusic&no_redirect=true")
        node = (j2 or {}).get("data", "")
        print(f"[取流] HTTP {code} 节点主机={node.split('/')[2] if node else '❌ 无'}")
        if node:
            hc, body = get(node, {"User-Agent": UA_M}, rng="bytes=0-2047")
            print(f"  节点直连 HTTP {hc} 前4字节={body[:4]!r}")

    # ---- ccMixter ----
    print("\n── ccMixter ──")
    code, body = get("https://ccmixter.org/api/query?f=json&limit=3&search=lofi")
    print(f"[搜索] HTTP {code}")
    try:
        arr = json.loads(body)
    except Exception:
        print("  ❌ 非 JSON:", body[:150])
        return
    for t in arr:
        mp3 = None
        size = 0
        ps = ""
        for f in (t.get("files") or []):
            fi = f.get("file_format_info") or {}
            if fi.get("mime_type") != "audio/mpeg":
                continue
            d = f.get("download_url", "")
            if not d:
                continue
            mp3, size, ps = d, f.get("file_filesize", 0), fi.get("ps", "")
            break
        artist = t.get("user_real_name") or t.get("user_name", "")
        print(f"  id={t.get('upload_id')} title={t.get('upload_name','')[:24]!r} artist={artist[:16]!r} "
              f"dur={ps} lic={t.get('license_name','')[:28]!r} mp3={'有' if mp3 else '❌ 无'}")
        if mp3:
            hc1, b1 = get(mp3, {"User-Agent": UA_M}, rng="bytes=0-2047")
            hc2, b2 = get(mp3, {"User-Agent": UA_M, "Referer": "https://ccmixter.org/"}, rng="bytes=0-2047")
            print(f"    无 Referer → HTTP {hc1}  有 Referer → HTTP {hc2} 前4字节={b2[:4]!r}"
                  f"  {'（证明确实需要 Referer，播放器已按 host 补）' if hc1 != hc2 else ''}")


def main():
    if "--open" in sys.argv:
        probe_open_sources()
        return
    if "--scan" in sys.argv:
        scan_kugou([
            ("晴天", "周杰伦"), ("温柔", "五月天"), ("起风了", "买辣椒也用券"),
            ("海阔天空", "Beyond"), ("Hello", "Adele"), ("Shape of You", "Ed Sheeran"),
            ("夜曲", "周杰伦"), ("孤勇者", "陈奕迅"),
        ])
        return
    title = sys.argv[1] if len(sys.argv) > 1 else "晴天"
    artist = sys.argv[2] if len(sys.argv) > 2 else "周杰伦"
    print(f"探测曲目: 「{title}」{artist}")
    probe_kugou(title, artist)
    probe_qq(title, artist)
    probe_netease(title, artist)
    print("\n" + "=" * 68)
    print("判读: 搜索✅+取流❌ → 接口还在，但取流被登录/会员挡住（可加 Cookie 解决）")
    print("      两环都❌       → 接口本身失效/改版，需要换端点或换方案")
    print("=" * 68)


if __name__ == "__main__":
    main()
