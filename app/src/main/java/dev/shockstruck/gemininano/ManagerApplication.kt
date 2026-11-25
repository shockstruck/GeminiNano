package dev.shockstruck.gemininano

import android.app.Application

class ManagerApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
