package com.buse.smartfreeze

import android.app.Application
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.buse.smartfreeze.data.RetentionWorker
import com.buse.smartfreeze.data.db.DeviceInfoEntity
import com.buse.smartfreeze.data.training.ModelTrainingWorker
import kotlinx.coroutines.launch

class SmartFreezeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        RetentionWorker.schedule(this)
        ModelTrainingWorker.schedule(this)

        container.appScope.launch {
            container.models.load()
            container.trainer.requestCheck()      // train the personal model if the baseline is already complete
            container.db.sessions().closeDangling()
            val r = container.metrics.read()
            container.monitoring.deviceInfo.upsert(
                DeviceInfoEntity(
                    manufacturer = Build.MANUFACTURER, model = Build.MODEL, sdkInt = Build.VERSION.SDK_INT,
                    totalRamBytes = r.memTotalBytes, thermalHeadroomSupported = r.thermalHeadroom?.let { true },
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
        }

        // Foreground-mode monitoring pauses when no SmartFreeze screen is visible.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.engine.onAppForegroundChanged(true)
            override fun onStop(owner: LifecycleOwner) = container.engine.onAppForegroundChanged(false)
        })
    }
}
