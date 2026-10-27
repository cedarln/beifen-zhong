package cn.linan.backingup

import java.io.File

/**
 * 单测定位剧本的唯一入口。
 *
 * 剧本的真相在仓库根 `script/`，不在 assets 的拷贝里：`assets.srcDir` 只是把同一个目录挂进包，
 * 所以测试直接读源目录，改剧本立刻能在 JVM 里看见，不用重打包、不用装设备。
 *
 * 从 `user.dir` 逐级往上找 `script/act1.json`——Gradle 的测试工作目录会随任务变化
 * （`android/`、`android/app/`、甚至是 fork 出来的临时目录），把路径写死第一次就挂。
 *
 * 三个测试类原先各抄了一份同样的四处查找，`user.dir` 又是 `String?`，
 * 编译器只能给 "Java type mismatch: inferred type is 'kotlin.String?'" 警告。
 * 收到这里以后只剩一处，也只剩一个 `?: "."` 兜底。
 */
internal fun scriptDir(): File {
    var dir: File? = File(System.getProperty("user.dir") ?: ".")
    while (dir != null) {
        val candidate = File(dir, "script")
        if (File(candidate, "act1.json").isFile()) return candidate
        dir = dir.parentFile
    }
    error("找不到仓库根的 script/ 目录（单测必须跑在仓库里）")
}
