package com.xiaohei.tv

import android.app.Application
import android.content.Context
import androidx.multidex.MultiDex

/** 只做两件事：4.4 上装 dex（方法数超 64K），以及尽早装上崩溃捕获。 */
class App : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 5.0+ 是原生 multidex，这里调用会被库自己忽略；4.4 必须在这步装第二段 dex，
        // 否则一启动就是 ClassNotFoundException。
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
    }
}
