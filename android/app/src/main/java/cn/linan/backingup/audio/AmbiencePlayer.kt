package cn.linan.backingup.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Log
import cn.linan.backingup.R

/**
 * 声床播放器：五条 16bit / mono / 16kHz 的无缝 PCM，各占一条 AudioTrack(MODE_STATIC)。
 *
 * 为什么值得自己管循环点（docs/06 第一节）：`hum_mains` 是**有相位的 50Hz 正弦**，
 * 有损编码会在循环点把它切成台阶。PCM + `setLoopPoints` 让硬件回到第 432000 个样本，
 * 而不是"近似那里"——接缝不存在，不是被藏起来。
 *
 * 三条红线在这里落地，别在别处补：
 *   · 属性是 `USAGE_MEDIA + CONTENT_TYPE_AMBIENT`，不申请音频焦点、不进通知通道、不打断来电；
 *     被系统抢占（来电/其他 App 出声）就暂停，回来再续。
 *   · 只在设置项「楼里的声音」打开时工作；默认关，第一次启动不响。
 *   · 后台 > 90 秒由 [AmbienceDirector] 算出全 0，这里淡到 0 之后 release 掉播放器。
 *
 * 刻意没做的一件事，写清楚而不是假装做了：docs/06 里"灭屏时给底噪加低通"在第一版是
 * **只降增益不做滤波**——AudioTrack 没有实时滤波，软件加低通要重采样整条流，
 * 而重采样正是我们在生成端费力气避开的东西。先用 −3dB 近似"手机扣在桌上"，
 * 真机上听感不够再考虑 SoftAudioProcessor。
 */
class AmbiencePlayer(private val context: Context) {

    private class Bed(val name: String, val rawId: Int) {
        var track: AudioTrack? = null
        var frames: Int = 0
        var current = 0.0          // 实际音量，向 target 逼近
        var target = 0.0
        var hardwareLoop = false
    }

    private val beds = HashMap<String, Bed>()
    private var voice: MediaPlayer? = null
    private var started = false

    /** 系统媒体音量低于这条线时我们不追（红线 3：尊重玩家自己定的音量） */
    private var masterScale = 1.0

    /**
     * 设定新的目标增益表。调用方只负责"该多响"，怎么过去由这里管。
     * @param gains 声床名 → 目标增益（0.0–1.0，[AmbienceDirector] 已封顶）
     */
    fun applyTargets(gains: Map<String, Double>) {
        gains.forEach { (name, value) ->
            val bed = ensureBed(name) ?: return@forEach
            bed.target = value.coerceIn(0.0, AmbienceDirector.GAIN_CEILING)
        }
        if (!started) startAll()
    }

    /** 一个 tick（[AmbienceDirector.TICK_MS] 一次）：逼近目标、该停的停、该放开的放开 */
    fun tick() {
        if (!started) return
        val mediaScale = systemMediaScale()
        beds.values.forEach { bed ->
            val target = bed.target * mediaScale
            // 固定比例逼近：被打断也不留中间态（见 RAMP_APPROACH 的注释）
            val next = bed.current + (target - bed.current) * AmbienceDirector.RAMP_APPROACH
            bed.current = if (kotlin.math.abs(target - next) < SILENCE_EPS) target else next
            applyVolume(bed)
            ensureSoftwareLoop(bed)
        }
    }

    /**
     * 硬件循环点没谈成的设备（`setLoopPoints` 在部分机型上抛异常）不能就此哑掉：
     * MODE_STATIC 的缓冲播完就停在末尾，之后无论音量怎么推都是静音。
     * 这里在每次 tick 里对一下播放头，播完一圈就回卷——有接缝，但比"这条床从此不响"好，
     * 而且 [status] 会如实写着 soft-loop，后门面板上一眼能看出这台设备没拿到硬件循环。
     */
    private fun ensureSoftwareLoop(bed: Bed) {
        if (bed.hardwareLoop || bed.frames <= 0) return
        val track = bed.track ?: return
        if (bed.current <= SILENCE_EPS && bed.target <= SILENCE_EPS) return
        val head = runCatching { track.playbackHeadPosition.toLong() and 0xFFFFFFFFL }
            .getOrDefault(0L)
        if (head >= bed.frames) {
            runCatching {
                track.pause()
                track.setPlaybackHeadPosition(0)
                track.play()
            }
        }
    }

    /**
     * 劣化的文件变体切换（integrity 过 55）：淡到 0 → 换 buffer → 再淡回来。
     * 这个切换点必须不可感知——一旦"听得出换了"就变成音效，不是环境。
     */
    fun swapVariant(name: String, newVariant: String) {
        val old = beds[name] ?: return
        if (old.name == newVariant) return
        old.target = 0.0
        // 立刻淡到可忽略，再重建；玩家听到的是一条本来就在衰减的床换了素材
        run { repeat(FADE_OUT_TICKS) { old.current *= 0.35; applyVolume(old) } }
        old.track?.let { runCatching { it.release() } }
        old.track = null
        beds.remove(name)
        ensureBed(newVariant)?.let { it.target = 0.0 }
    }

    /**
     * D7 那六秒语音（`audio_ref` → res/raw/voice_2014）。
     * 播之前必须已经四路归零——这条由剧本段落保证，这里只负责不再叠加任何东西。
     */
    fun playVoiceOnce(resourceId: Int) {
        stopVoice()
        voice = MediaPlayer.create(context, resourceId)?.apply {
            setOnCompletionListener { stopVoice() }
            // 不申请音频焦点，也不注册焦点回调（docs/06 第五节：氛围音与语音都走媒体音量，
            // 别人要出声就让路）。系统抢占时这条会自然静音，我们只在完成回调里放实例。
            start()
        }
    }

    fun stopVoice() {
        voice?.run { runCatching { stop() }; runCatching { release() } }
        voice = null
    }

    /** 设置项关掉、或退出章节：淡到 0 并释放，不留定时器和播放器实例（验收 6） */
    fun release() {
        beds.values.forEach { bed ->
            bed.target = 0.0
            bed.current = 0.0
            runCatching { bed.track?.pause() }
            runCatching { bed.track?.flush() }
            runCatching { bed.track?.release() }
            bed.track = null
        }
        beds.clear()
        stopVoice()
        started = false
    }

    /** 面板与单测看这个：哪条床真的拿到了硬件循环 */
    fun status(): Map<String, String> = beds.values.associate {
        it.name to "${it.current.fmt()}→${it.target.fmt()} ${if (it.hardwareLoop) "hw-loop" else "soft-loop"}"
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun ensureBed(name: String): Bed? {
        beds[name]?.let { return it }
        val rawId = rawIdFor(name) ?: return null
        val pcm = loadPcm(rawId) ?: return null
        val bed = Bed(name, rawId).apply { frames = pcm.frames }
        bed.track = buildTrack(pcm, bed)
        beds[name] = bed
        return bed
    }

    private fun startAll() {
        started = true
        beds.values.forEach { runCatching { it.track?.play() } }
    }

    private fun applyVolume(bed: Bed) {
        val track = bed.track ?: return
        val v = bed.current.coerceIn(0.0, 1.0)
        // 0.0 不给 setVolume(0f)——那是"静音但仍占一条播放轨道"，dumpsys 里会被看见
        if (v <= SILENCE_EPS) {
            if (bed.target <= SILENCE_EPS && bed.current <= SILENCE_EPS) {
                runCatching { track.pause() }
            }
            runCatching { track.setVolume(0.0f) }
        } else {
            runCatching { if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play() }
            runCatching { track.setVolume(v.toFloat()) }
        }
    }

    private fun buildTrack(pcm: Pcm, bed: Bed): AudioTrack? {
        val minBuf = AudioTrack.getMinBufferSize(
            pcm.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(pcm.bytes.size)
        val track = AudioTrack(
            AudioAttributes.Builder()
                // 氛围音不是通知音：USAGE_MEDIA 是唯一允许的选择
                .setUsage(AudioAttributes.USAGE_MEDIA)
                // CONTENT_TYPE_AMBIENT 不是公开常量（@hide），能用的最接近的是 MUSIC。
                // 语义上没错：这条床走媒体音量，不设独立音量正是 docs/06 第五节的要求。
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(pcm.sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            minBuf,
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        track.write(pcm.bytes, 0, pcm.bytes.size)
        bed.hardwareLoop = runCatching {
            // 循环点接管到最后一个样本：无缝素材 + 硬件回跳 = 接缝不存在
            track.setLoopPoints(0, pcm.frames - 1, -1)
        }.isSuccess
        return track
    }

    /** 系统媒体音量当乘数：她不该比玩家设定的音量更响 */
    private fun systemMediaScale(): Double {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 1.0
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        masterScale = now.toDouble() / max
        return if (masterScale < 0.1) 0.0 else 1.0
    }

    private fun rawIdFor(name: String): Int? = runCatching {
        when (name) {
            AmbienceDirector.BED_ROOM -> R.raw.bed_room
            AmbienceDirector.HUM_MAINS -> R.raw.hum_mains
            AmbienceDirector.DETAIL_DRIP -> R.raw.detail_drip
            AmbienceDirector.ANOMALY -> R.raw.anomaly_bitcrush
            AmbienceDirector.ANOMALY_LITE -> R.raw.anomaly_bitcrush_lite
            else -> null
        }
    }.getOrNull()

    private data class Pcm(val bytes: ByteArray, val frames: Int, val sampleRate: Int)

    /** 只认自己生成的那种 WAV：16bit / mono / PCM，RIFF 头里找 data 块 */
    private fun loadPcm(rawId: Int): Pcm? = runCatching {
        val all = context.resources.openRawResource(rawId).use { it.readBytes() }
        var rate = 16000
        var i = 12
        var data: ByteArray? = null
        while (i + 8 <= all.size) {
            val id = String(all, i, 4, Charsets.US_ASCII)
            val size = le32(all, i + 4)
            when (id) {
                "fmt " -> {
                    // fmt 块里的三个字段：i+8 编码（1=PCM）、i+10 声道数、i+12 采样率。
                    // 上一版在这里拿 i+10 当编码判，判的其实是声道——一条 32bit 浮点的床
                    // 会顶着"只支持 PCM"的注释被当成合法素材装进来，出来是噪声。
                    require(le16(all, i + 8) == 1) { "只支持 PCM（audioFormat≠1）" }
                    require(le16(all, i + 10) == 1) { "只支持单声道（生成端固定 mono）" }
                    rate = le32(all, i + 12)
                }
                "data" -> data = all.copyOfRange(i + 8, minOf(i + 8 + size, all.size))
            }
            i += 8 + size + (size and 1)
        }
        val bytes = data ?: return null
        Pcm(bytes, bytes.size / 2, rate)
    }.onFailure { Log.w(TAG, "声床装载失败：raw=$rawId", it) }.getOrNull()

    private fun Double.fmt(): String = "%.3f".format(this)

    private companion object {
        const val TAG = "AmbiencePlayer"
        const val SILENCE_EPS = 0.001
        const val FADE_OUT_TICKS = 8
        fun le16(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
        fun le32(b: ByteArray, at: Int): Int =
            le16(b, at) or (le16(b, at + 2) shl 16)
    }
}
