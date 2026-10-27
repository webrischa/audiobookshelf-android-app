package com.audiobookshelf.nativeapp

import android.app.Application

class AbsApp : Application() {
  lateinit var container: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    container = AppContainer(this)
  }
}
