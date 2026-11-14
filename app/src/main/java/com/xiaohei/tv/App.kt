package com.xiaohei.tv

import android.app.Application

/** 只做一件事：尽早装上崩溃捕获，保证 MainActivity 之外的组件崩溃也能留痕。 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
    }
}
