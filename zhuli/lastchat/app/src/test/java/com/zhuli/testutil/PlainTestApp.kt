package com.zhuli.testutil

import android.app.Application

/**
 * Robolectric 下的干净 Application。
 *
 * 默认的 LastChatApp.onCreate() 会启动 Koin + Firebase + WorkManager，在 JVM 单测里
 * 要么 Firebase 占位配置失败，要么多个测试间 Koin 重复 start（KoinApplicationAlreadyStartedException）。
 * 记忆 / Agent 的 SQLite 测试只依赖真 Context + 真 SQLiteOpenHelper，不需要任何 App 初始化，
 * 所以用这个空 Application 替代（通过 @Config(application = PlainTestApp::class) 指定）。
 */
class PlainTestApp : Application()
