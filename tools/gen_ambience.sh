#!/usr/bin/env bash
# 生成《正在备份》的氛围声床：4 条无缝循环 stem + D7 那句六秒语音的占位版
#
# 为什么程序化合成而不是找素材：
#   1) 零版权风险、离线可跑；四条声床 PCM 合计约 3.3MB（16kHz 单声道已经是最省的口径）；
#   2) anomaly 这条要能被完好度(integrity)实时劣化——素材库给的是"更破的循环"，
#      合成给的是同一个文件的不同降采样链，玩家听到的劣化就是她被清除的进度；
#   3) 圣经要求不靠配乐推情绪：这四条都是『环境的音量』，没有旋律性乐器、没有人声。
#      全篇唯一人声仍然是 d7_n711 那 6 秒，它必须继续是唯一一次"有东西开口说话"。
#
# 为什么成品是无损 PCM 而不是 ogg/mp3：hum_mains 是有相位的 50Hz 正弦，任何有损编码器
# 在循环点的 priming gap 上都会『咔』，而我们要引擎在精确样本处接管循环点。体积换确定性。
#
# 用法：bash tools/gen_ambience.sh [输出目录]      # 默认 android/app/src/main/res/raw
set -euo pipefail

OUT="${1:-android/app/src/main/res/raw}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# seed 写死：换 seed 等于换素材，得重跑 tools/check_ambience.py 并重听。
# 同一条声床的两个图层必须用不同 seed，否则'两路独立噪声'其实是同一条序列过不同滤波器。
SR=16000                       # 全流程一个采样率。中间不重采样——重采样器在文件两端
                               # 用的是零填充而不是循环邻域，接缝会在源头就坏掉（实测过）
SRC=30                         # 生成时长
X=3                            # 首尾交叉淡化区 → 成品 27 秒无缝循环
REF_LUFS=-28                   # 四条声床统一到这个响度（见 docs/06：增益数字要跨声床可比）
PEAK_CEIL=-7                   # 峰值天花板。比契约（−6 dBFS）再留 1 dB：
                               # 顶在 −6.000 上算不过检验，而且四路一叠就是贴边削顶
mkdir -p "$OUT"

measure() {                        # measure <lufs|peak> <file> → 打印一个 dB 数
  if [ "$1" = lufs ]; then
    ffmpeg -hide_banner -v info -i "$2" -filter ebur128 -f null - 2>&1 \
      | awk '$1=="I:"{v=$2} END{print v}'
  else
    ffmpeg -hide_banner -v info -i "$2" -filter volumedetect -f null - 2>&1 \
      | awk '{for(i=1;i<=NF;i++) if($i=="max_volume:"){v=$(i+1); gsub(/[^0-9.-]/,"",v)}} END{print v}'
  fi
}

seamless() {                       # seamless <in.wav> <out.wav>
  # 正确做法：out = acrossfade( S[X:T], S[0:X] )，成品长度 T-X。
  # 这样成品的『末尾』是头部内容的延续，循环回去时接得上；
  # 反过来写（把尾部淡进头部）对纯噪声碰巧听不出来，对 50Hz 这类有相位的内容会咔。
  # 末尾再 atrim 一次：aecho 这类带尾音的滤镜会把 body 撑长 71ms，不剪掉的话循环周期
  # 就不是 27 秒，门控/LFO 的相位每圈漂一次。周期必须整除 27，引擎才接管得住。
  # 这里刻意不做响度归一化：loudnorm 是带包络跟踪和内部 4× 重采样的动态处理，
  # 稀疏素材（drip）会被它『泵』起来，而且它会把重采样伪影正好留在循环点上。
  # 另外在这里就拍成单声道：ffmpeg 的 ebur128 对立体声和它自己的 -ac 1 单声道结果
  # 差 6 dB（实测），先测立体声、后出单声道，归一化就会整体偏小 6 dB。测量对象必须
  # 就是交付对象。
  ffmpeg -y -hide_banner -loglevel error -i "$1" -filter_complex \
    "[0:a]asplit[a][b];\
     [b]atrim=0:$X,asetpts=PTS-STARTPTS[head];\
     [a]atrim=$X,asetpts=PTS-STARTPTS[body];\
     [body][head]acrossfade=d=$X:c1=tri:c2=tri,\
     atrim=0:$((SRC - X)),asetpts=PTS-STARTPTS" -ac 1 -ar $SR "$2"
}

finalize() {                       # finalize <in.wav> <out.wav> <名字>
  # 只用一个标量增益把成品推到 REF_LUFS：乘法逐样本、无记忆、无边界效应，
  # 循环点不会被破坏。峰值天花板优先——顶不住就少推一点，宁可差 1 个 LU。
  local i p g
  i=$(measure lufs "$1"); p=$(measure peak "$1")
  g=$(awk -v t="$REF_LUFS" -v i="$i" 'BEGIN{printf "%.2f", t - i}')
  g=$(awk -v g="$g" -v p="$p" -v c="$PEAK_CEIL" 'BEGIN{ if (p + g > c) printf "%.2f", c - p; else printf "%.2f", g }')
  ffmpeg -y -hide_banner -loglevel error -i "$1" -af "volume=${g}dB" -ac 1 -ar $SR -c:a pcm_s16le "$2"
  printf '  %-20s 循环后 I=%s/峰值 %s dBFS → 加 %+6s dB → 成品 I=%s LUFS / 峰值 %s dBFS\n' \
    "$3" "$i" "$p" "$g" "$(measure lufs "$2")" "$(measure peak "$2")"
}

# ── 1) bed_room：楼里的房间声。两路独立噪声各自压低通 + 极慢起伏，立体声拉开宽度 ──
ffmpeg -y -hide_banner -loglevel error \
  -f lavfi -i "anoisesrc=colour=pink:amplitude=0.11:seed=141:duration=$SRC:sample_rate=$SR" \
  -f lavfi -i "anoisesrc=colour=brown:amplitude=0.09:seed=707:duration=$SRC:sample_rate=$SR" \
  -filter_complex \
    "[0:a]lowpass=f=380,volume='0.75+0.25*sin(2*PI*(2/27)*t)':eval=frame,volume=-13dB[L];\
     [1:a]lowpass=f=260,volume='0.80+0.20*sin(2*PI*(1/27)*t+1.3)':eval=frame,volume=-15dB[R];\
     [L][R]amerge=inputs=2" -ar $SR "$TMP/bed.wav"
seamless "$TMP/bed.wav" "$TMP/bed_loop.wav"

# ── 2) hum_mains：工频哼鸣。50Hz 基音 + 150Hz 谐波 + 极细高频闪噪（日光灯） ──────
#    tremolo 频率取 6/27=0.2222Hz：一个 27 秒循环里正好 6 个完整起伏，接缝不跳。
#    50Hz×27s=1350、150Hz×27s=4050 都是整数周期，所以正弦在循环点上相位天然相接。
ffmpeg -y -hide_banner -loglevel error \
  -f lavfi -i "sine=frequency=50:duration=$SRC:sample_rate=$SR" \
  -f lavfi -i "sine=frequency=150:duration=$SRC:sample_rate=$SR" \
  -f lavfi -i "anoisesrc=colour=white:amplitude=0.03:seed=2014:duration=$SRC:sample_rate=$SR" \
  -filter_complex \
    "[0:a]volume=0.55,pan=stereo|c0=c0|c1=0.92*c0[a0];\
     [1:a]volume=0.16,tremolo=f=0.2222:d=0.6[a1];\
     [2:a]highpass=f=2800,lowpass=f=6200,volume=-22dB,tremolo=f=8:d=0.7[a2];\
     [a0][a1][a2]amix=inputs=3:normalize=0,volume=-8dB" -ar $SR "$TMP/hum.wav"
seamless "$TMP/hum.wav" "$TMP/hum_loop.wav"

# ── 3) detail_drip：具体名词的声音。稀疏水滴 + 瓷砖房间的短回声，靠门控表达式产生 ──
ffmpeg -y -hide_banner -loglevel error \
  -f lavfi -i "anoisesrc=colour=white:amplitude=0.5:seed=307:duration=$SRC:sample_rate=$SR" \
  -filter_complex \
    "[0:a]bandpass=f=1450:width_type=h:w=420,\
     volume='if(lt(mod(t+0.7,5.4),0.028),0.9,if(lt(mod(t+2.9,13.5),0.02),0.5,0.0015))':eval=frame,\
     aecho=0.85:0.55:38|71:0.42|0.2,volume=-6dB,highpass=f=300" -ar $SR "$TMP/drip.wav"
seamless "$TMP/drip.wav" "$TMP/drip_loop.wav"

# ── 4) anomaly_bitcrush：劣化声床。降采样保持 + 随机结巴 ──────────────────────────
#      引擎侧按 integrity 选文件变体（本条为 9k 档，integrity 低时播放；高时 14k 档见 -lite）
ffmpeg -y -hide_banner -loglevel error \
  -f lavfi -i "anoisesrc=colour=brown:amplitude=0.35:seed=55:duration=$SRC:sample_rate=$SR" \
  -f lavfi -i "anoisesrc=colour=pink:amplitude=0.18:seed=555:duration=$SRC:sample_rate=$SR" \
  -filter_complex \
    "[0:a]aformat=sample_rates=9000:sample_fmts=s16,aresample=$SR,\
     volume='if(lt(mod(t,2.7),0.34),0.8,0.05)':eval=frame[a];\
     [1:a]aformat=sample_rates=14000:sample_fmts=s16,aresample=$SR,lowpass=f=1700[b];\
     [a][b]amix=inputs=2:normalize=0,volume=-9dB" -ar $SR "$TMP/anom.wav"
seamless "$TMP/anom.wav" "$TMP/anom_loop.wav"

# ── 4b) anomaly_bitcrush_lite：劣化的轻档（integrity ≥ 55 时播这一条）──────────────
#      同一条素材，只走 14k 那条链、门控压得浅——这是『同一个文件的不同降采样链』
#      那句话的兑现，不是"找一段更破的循环"。引擎在 55 处淡到 0 再换文件，听不出切换。
ffmpeg -y -hide_banner -loglevel error \
  -f lavfi -i "anoisesrc=colour=pink:amplitude=0.20:seed=56:duration=$SRC:sample_rate=$SR" \
  -filter_complex \
    "[0:a]aformat=sample_rates=14000:sample_fmts=s16,aresample=$SR,lowpass=f=1700,\
     volume='if(lt(mod(t,2.7),0.34),0.5,0.15)':eval=frame" -ar $SR "$TMP/anoml.wav"
seamless "$TMP/anoml.wav" "$TMP/anoml_loop.wav"

echo "响度归一（统一到 ${REF_LUFS} LUFS / 峰值天花板 ${PEAK_CEIL} dBFS）："
for stem in bed:bed_room hum:hum_mains drip:detail_drip anom:anomaly_bitcrush anoml:anomaly_bitcrush_lite; do
  src="${stem%%:*}"; name="${stem##*:}"
  finalize "$TMP/${src}_loop.wav" "$OUT/${name}.wav" "$name"
done

# ── 5) d7_n711 的占位语音（六秒，全篇唯一人声） ──────────────────────────────────
#    ⚠ 这不是成品：用 macOS 的 say 出一版占位。正式发行必须换成真实录音
#    （她妈妈的口音、先咳一下、20:14 的时间戳感）。圣经规则：这六秒之前不许出现任何人声。
VOICE_TXT="妈不打扰你。妈就想问你一句，明天那个面试，你别迟到。"
PLACEHOLDER=0
if command -v say >/dev/null 2>&1; then
  if say -v Tingting -r 165 -o "$TMP/voice.caf" "$VOICE_TXT" 2>/dev/null && [ -f "$TMP/voice.caf" ]; then
    DUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$TMP/voice.caf")
    ffmpeg -y -hide_banner -loglevel error -i "$TMP/voice.caf" \
      -f lavfi -i "anoisesrc=colour=pink:amplitude=0.018:duration=${DUR}:sample_rate=22050" \
      -filter_complex \
        "[0:a]highpass=f=190,lowpass=f=5200,volume=8dB,aecho=0.8:0.3:26:0.18[v];\
         [v][1:a]amix=inputs=2:normalize=0" \
      -t 6.4 -c:a aac -b:a 48k -ar 22050 "$OUT/voice_2014.m4a"
    PLACEHOLDER=1
  fi
fi

echo
echo "生成完成 → $OUT"
for f in "$OUT"/*.wav; do
  [ -e "$f" ] || continue
  printf '  %-26s %6s  %ss  %s\n' "$(basename "$f")" "$(du -h "$f" | cut -f1)" \
    "$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$f" | cut -c1-6)" \
    "$(ffprobe -v error -show_entries stream=sample_rate,channels -of csv=p=0 "$f")"
done
[ "$PLACEHOLDER" = 1 ] && printf '  %-26s %6s  ⚠占位，待真实录音\n' "voice_2014.m4a" "$(du -h "$OUT/voice_2014.m4a" | cut -f1)"
echo
echo "声床名（须与 script/*.json 的 ambience.bed 一致）：bed_room · hum_mains · detail_drip · anomaly_bitcrush"
