package com.dengdeng.music.ui.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * 主题设置 DataStore（全局共享单例）
 *
 * 必须只有一个 preferencesDataStore(name = "theme_prefs") 委托声明——
 * MainActivity（读写）与桌面小组件 WidgetUpdater（只读主题色）共用本实例。
 * 重复声明同名文件会抛 "multiple DataStores active" 异常。
 */
val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_prefs")
