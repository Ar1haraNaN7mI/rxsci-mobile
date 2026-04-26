package com.x.rxsciapp

import android.app.Application
import com.x.rxsciapp.app.AppContainer

class RxsciApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
