package cn.linan.backingup.engine

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 存档读写。
 *
 * 一个 key、一段 JSON：存档必须能一眼看完——后门面板要改它、结局《备份》要导它、
 * 排查"闹钟没响"时我要能 adb pull 出来读。上 Room 只会让这三件事都变难。
 * schema 字段留着，改结构时做一次性迁移，不做兼容读取。
 *
 * allowBackup=false（manifest）+ 只落 internal：她的存在就是本地缓存，
 * 云备份会把"卸载即真消失"从设定变成 bug。
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "backing_up_save")

class SaveStore(private val context: Context) {

    private val gson: Gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()

    /** UI 侧的唯一状态源：冷启动读一次，之后每次写都推新的 SaveFile */
    val save: Flow<SaveFile> = context.dataStore.data.map { decodeJson(it[KEY_SAVE]) }

    /** 一次性读，给调度器与 catchUp 用 */
    suspend fun read(): SaveFile = decodeJson(context.dataStore.data.first()[KEY_SAVE])

    suspend fun write(save: SaveFile) {
        require(save.schema == SaveFile.SCHEMA) {
            "存档 schema ${save.schema} ≠ ${SaveFile.SCHEMA}：先写迁移，别硬读"
        }
        context.dataStore.edit { it[KEY_SAVE] = gson.toJson(save) }
    }

    /** 后门面板的"清空重开"。玩家侧永远看不到这个入口 */
    suspend fun wipe() {
        context.dataStore.edit { it.clear() }
    }

    private fun decodeJson(raw: String?): SaveFile =
        if (raw.isNullOrBlank()) SaveFile() else gson.fromJson(raw, SaveFile::class.java)

    companion object {
        val KEY_SAVE = stringPreferencesKey("save_json")
    }
}
