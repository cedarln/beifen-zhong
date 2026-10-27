#!/usr/bin/env python3
"""
check_ambience.py — 声床成品的客观检验（循环接缝 / 格式 / 峰值 / 响度）
产物是 4 条基准声床 + 1 条 anomaly 轻档，共 5 个 stem。

为什么要有这个脚本：耳朵在 −28LUFS 的低增益声床上不可靠，而「循环点咔一下」是这个
项目最容易毁掉沉浸感、又最难在手机上复现的问题。所以把它变成数字，写完就别再猜。
只用标准库 + ffmpeg/ffprobe（和 tools/ 里其他脚本一致）。

用法：
    python3 tools/check_ambience.py android/app/src/main/res/raw
退出码：0 = 全部通过；1 = 有不合格项。

判据（与 tools/gen_ambience.sh 的常量一一对应，改那边要改这边）：
  1. 时长必须精确 27.000s ±1 帧——引擎按样本接管循环点，长短不齐 = 相位每圈漂移。
  2. pcm_s16le / 单声道 / 16000Hz。有损编码一律拒绝（hum 是有相位的 50Hz 正弦，
     任何编码器在循环 priming gap 上都会『咔』）。
  3. 循环接缝：把两圈拼起来，接缝 ±50ms 窗口内的最大相邻样本差，不得超过全曲
     最大相邻差的 1.5 倍。超过 = 循环点听得出东西。
  4. 峰值 ≤ −6 dBFS：四路叠加时的 headroom 契约。
  5. 响度 = −28 LUFS ±2：全部统一到同一参考响度，剧本里的增益数字才跨声床可比。
"""
import array
import math
import os
import subprocess
import sys
import wave

SR = 16000
EXPECT_LEN = 27.0
TOL_SAMPLES = 8                    # ±8 个样本，四舍五入的余量
SEAM_WINDOW_S = 0.05
SEAM_RATIO_LIMIT = 1.5
PEAK_DBFS_MAX = -6.0
REF_LUFS = -28.0
LUFS_TOL = 2.0
BEDS = ["bed_room", "hum_mains", "detail_drip", "anomaly_bitcrush", "anomaly_bitcrush_lite"]


def read_pcm(path):
    """stdlib wave 直接读，不经 ffmpeg：峰值和接缝差必须逐样本，不能混进重采样。"""
    with wave.open(path, "rb") as w:
        meta = {"sample_rate": w.getframerate(),
                "channels": w.getnchannels(),
                "nframes": w.getnframes(),
                "sampwidth": w.getsampwidth()}
        raw = w.readframes(w.getnframes())
    a = array.array("h")
    a.frombytes(raw)
    if sys.byteorder == "big":
        a.byteswap()
    scale = 1.0 / 32768.0
    return [x * scale for x in a], meta


def integrated_lufs(path):
    """真·EBU R128 积分响度，交给 ffmpeg 的 ebur128（自己糊一个不叫 LUFS）。"""
    out = subprocess.run(
        ["ffmpeg", "-hide_banner", "-v", "info", "-i", path, "-filter", "ebur128",
         "-f", "null", "-"], capture_output=True, text=True).stderr
    value = None
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[0] == "I:":
            try:
                value = float(parts[1])
            except ValueError:
                pass
    return value


def peak_dbfs(samples):
    m = max(abs(s) for s in samples)
    return 20.0 * math.log10(m) if m > 1e-12 else -120.0


def max_adjacent_diff(seq):
    prev = seq[0]
    best = 0.0
    for s in seq[1:]:
        d = abs(s - prev)
        if d > best:
            best = d
        prev = s
    return best


def seam_ratio(samples):
    """两圈拼接后接缝处的最大相邻差 / 全曲最大相邻差。"""
    global_max = max_adjacent_diff(samples)
    w = int(SEAM_WINDOW_S * SR)
    seam_max = max_adjacent_diff(samples[-w:] + samples[:w])
    ratio = seam_max / global_max if global_max > 1e-12 else 0.0
    return ratio, seam_max, global_max


def main(raw_dir):
    if not os.path.isdir(raw_dir):
        print(f"目录不存在：{raw_dir}")
        return 1
    failures = []
    print(f"{'声床':20s} {'时长':>9s} {'格式':>22s} {'峰值':>9s} {'响度':>10s} {'接缝比':>8s}")
    print("─" * 82)
    total_bytes = 0
    for name in BEDS:
        path = os.path.join(raw_dir, name + ".wav")
        if not os.path.isfile(path):
            failures.append(f"{name}: 文件缺失（先跑 tools/gen_ambience.sh）")
            continue
        total_bytes += os.path.getsize(path)
        samples, meta = read_pcm(path)
        dur = len(samples) / meta["sample_rate"]
        peak = peak_dbfs(samples)
        lufs = integrated_lufs(path)
        ratio, seam_max, global_max = seam_ratio(samples)
        fmt = f"{meta['sampwidth'] * 8}bit/{meta['channels']}ch/{meta['sample_rate']}Hz"

        print(f"{name:20s} {dur:8.3f}s {fmt:>22s} {peak:8.1f}dB "
              f"{('%7.1f LUFS' % lufs) if lufs is not None else '   n/a':>10s} {ratio:7.2f}x")

        if abs(len(samples) - EXPECT_LEN * SR) > TOL_SAMPLES:
            failures.append(f"{name}: 时长 {dur:.3f}s ≠ {EXPECT_LEN}s，循环相位每圈漂一次")
        if meta["sample_rate"] != 16000 or meta["channels"] != 1 or meta["sampwidth"] != 2:
            failures.append(f"{name}: 必须是 16bit/mono/16kHz PCM，实测 {fmt}")
        if peak > PEAK_DBFS_MAX:
            failures.append(f"{name}: 峰值 {peak:.1f}dBFS > {PEAK_DBFS_MAX}，四路叠加会削顶")
        if lufs is None:
            failures.append(f"{name}: 读不到 ebur128 积分响度（ffmpeg 版本问题？）")
        elif abs(lufs - REF_LUFS) > LUFS_TOL:
            failures.append(f"{name}: 响度 {lufs:.1f} LUFS 偏离参考 {REF_LUFS:.0f} "
                            f"{lufs - REF_LUFS:+.1f} LU（剧本增益会失真）")
        if ratio > SEAM_RATIO_LIMIT:
            failures.append(f"{name}: 接缝差 {seam_max:.5f} 是全曲最大跳变 {global_max:.5f} 的 "
                            f"{ratio:.2f} 倍（>{SEAM_RATIO_LIMIT}）→ 循环点听得出『咔』")

    print("─" * 82)
    print(f"全部声床合计 {total_bytes / 1024 / 1024:.2f} MB。PCM 压不动（噪声熵高），"
          f"打进 APK 基本就是这个数——16kHz 单声道已经是最省的口径。")
    if failures:
        print("\n不合格项：")
        for f in failures:
            print("  ✗ " + f)
        return 1
    print("\n全部通过：循环点、格式、峰值、参考响度都在判据内。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "android/app/src/main/res/raw"))
