package cn.linan.backingup.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * 玩家设置：只有三项，且都必须"默认不帮倒忙"。
 *
 * 「楼里的声音」默认 **off**（docs/06 红线 3）：恐怖游戏偷偷放声音是越界，不是沉浸。
 * 第一次进章节时由 UI 用一行小字邀请开启，不弹窗。
 * 「夜里也让她说」默认 **off**（docs/02 的投递礼貌窗口）：23:30–07:30 她的话不进通知栏，
 * 推到早上 07:30。游戏时钟与存档不动，动的只是"什么时候吵你"。
 * 「快速模式」默认 off，通关一次后才由剧本解锁——它是玩家层的能力，不是开发层的。
 *
 * 和存档分开存：存档里装的是"她那边发生了什么"，设置里装的是"你这台设备怎么放声音、
 * 什么时候可以吵你"。混在一个 JSON 里会让结局《备份》的导出文件带上无关噪声。
 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "backing_up_settings")

class SettingsStore(private val context: Context) {

    suspend fun ambienceEnabled(): Boolean = read(AmbienceEnabled) ?: false

    suspend fun setAmbienceEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[AmbienceEnabled] = enabled }
    }

    /** 夜间投递授权（[cn.linan.backingup.sched.QuietHours] 的唯一开关），默认不给 */
    suspend fun nightDeliveryEnabled(): Boolean = read(NightDeliveryOn) ?: false

    suspend fun setNightDeliveryEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[NightDeliveryOn] = enabled }
    }

    /** 那行邀请只出现一次：第二次启动还看见提示，就像 App 在讨要权限 */
    suspend fun ambienceInvited(): Boolean = read(AmbienceInvited) ?: false

    suspend fun markAmbienceInvited() {
        context.settingsDataStore.edit { it[AmbienceInvited] = true }
    }

    /**
     * 恢复默认：把上面三条一起清掉，回到"刚装好"的样子。
     *
     * 只清设置，**不碰存档**。存档里装的是"她那边发生过什么"，设置里装的是"这台设备
     * 怎么放声音、什么时候可以吵你"——把一颗『恢复默认』顺手做成清档是最危险的误设计；
     * 真要重来一次是后门面板里『清空重开』那一颗（玩家侧看不到它，见 docs/04）。
     * 系统权限（通知、精确唤醒）也不在这里：那是玩家在系统里给的，我们撤不掉，
     * 也不该假装撤得掉——设置页那两行状态照旧显示真实值。
     */
    suspend fun resetAll() {
        context.settingsDataStore.edit { it.clear() }
    }

    val ambienceFlow: Flow<Boolean> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[AmbienceEnabled] ?: false }

    private suspend fun <T> read(key: Preferences.Key<T>): T? =
        context.settingsDataStore.data
            .catch { emit(emptyPreferences()) }
            .map { it[key] }
            .first()

    private companion object {
        val AmbienceEnabled = booleanPreferencesKey("ambience_on")
        val AmbienceInvited = booleanPreferencesKey("ambience_invited")
        val NightDeliveryOn = booleanPreferencesKey("night_delivery_on")
    }
}
