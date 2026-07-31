package com.hippo.ehviewer.task.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.FileObserver
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.FavouriteStatusRouter
import com.hippo.ehviewer.client.data.LocalGalleryInfo
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.local.LocalGalleryManager
import com.hippo.ehviewer.network.NetworkStateManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue

/**
 * 事件触发器分发器：集中订阅所有事件源，把它们转换为 AutomationEvent 并投递给 AutomationManager。
 */
class EventTriggerDispatcher private constructor(private val context: Context) {

    companion object {
        private const val TAG = "EventTriggerDispatcher"

        @Volatile
        private var instance: EventTriggerDispatcher? = null

        @JvmStatic
        fun getInstance(context: Context): EventTriggerDispatcher {
            return instance ?: synchronized(this) {
                instance ?: EventTriggerDispatcher(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appContext = context.applicationContext
    private val downloadManager: DownloadManager by lazy { EhApplication.getDownloadManager(appContext) }
    private val networkStateManager: NetworkStateManager get() = NetworkStateManager
    private val favouriteRouter: FavouriteStatusRouter by lazy { EhApplication.getFavouriteStatusRouter(appContext) }
    private val localGalleryManager: LocalGalleryManager by lazy { LocalGalleryManager.getInstance(appContext) }
    private val manager: AutomationManager by lazy { AutomationManager.getInstance(appContext) }

    private val registered = CopyOnWriteArrayList<AutoCloseable>()
    private val fileObservers = ConcurrentHashMap<String, FileObserver>()
    private val intentReceivers = ConcurrentHashMap<String, BroadcastReceiver>()
    private var batteryReceiver: BroadcastReceiver? = null
    private var initialized = false

    @Synchronized
    fun initialize() {
        if (initialized) return
        registerDownloadListeners()
        registerNetworkListener()
        registerFavouriteListener()
        registerLocalGalleryListener()
        registerProcessLifecycle()
        registerBatteryReceiver()
        initialized = true
        Log.i(TAG, "EventTriggerDispatcher initialized")
    }

    @Synchronized
    fun shutdown() {
        registered.forEach {
            try { it.close() } catch (e: Exception) { Log.w(TAG, "Failed to close listener", e) }
        }
        registered.clear()
        fileObservers.values.forEach { it.stopWatching() }
        fileObservers.clear()
        intentReceivers.values.forEach {
            try { appContext.unregisterReceiver(it) } catch (_: Exception) {}
        }
        intentReceivers.clear()
        batteryReceiver?.let {
            try { appContext.unregisterReceiver(it) } catch (_: Exception) {}
        }
        batteryReceiver = null
        initialized = false
    }

    fun watchFile(path: String, onCreated: (String) -> Unit, onDeleted: (String) -> Unit) {
        stopWatchingFile(path)
        val observer = object : FileObserver(File(path)) {
            override fun onEvent(event: Int, file: String?) {
                if (file == null) return
                val fullPath = if (path.endsWith("/")) "$path$file" else "$path/$file"
                when (event) {
                    CREATE, MOVED_TO -> onCreated(fullPath)
                    DELETE, MOVED_FROM -> onDeleted(fullPath)
                }
            }
        }
        observer.startWatching()
        fileObservers[path] = observer
    }

    fun stopWatchingFile(path: String) {
        fileObservers.remove(path)?.stopWatching()
    }

    fun registerIntentFilter(action: String, callback: (Intent) -> Unit) {
        stopIntentFilter(action)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent?.let(callback)
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        intentReceivers[action] = receiver
    }

    fun stopIntentFilter(action: String) {
        intentReceivers.remove(action)?.let {
            try { appContext.unregisterReceiver(it) } catch (_: Exception) {}
        }
    }

    fun postEvent(event: AutomationEvent) {
        manager.onEvent(event)
    }

    private fun registerDownloadListeners() {
        val listener = object : DownloadManager.DownloadListener {
            override fun onStart(info: DownloadInfo) {}
            override fun onGet509() {}
            override fun onDownload(info: DownloadInfo) {}
            override fun onGetPage(info: DownloadInfo) {}
            override fun onPhaseChanged(info: DownloadInfo, phase: Int) {}
            override fun onCancel(info: DownloadInfo) {
                postEvent(AutomationEvent(EventTriggerType.DOWNLOAD_FAILED, payload = mapOf(
                    AutomationEvent.KEY_GID to info.gid.toString(),
                    AutomationEvent.KEY_LABEL to (info.label ?: ""),
                )))
            }
            override fun onFinish(info: DownloadInfo) {
                val finished = info.state == DownloadInfo.STATE_FINISH
                val type = if (finished) EventTriggerType.DOWNLOAD_FINISHED else EventTriggerType.DOWNLOAD_FAILED
                postEvent(AutomationEvent(type, payload = mapOf(
                    AutomationEvent.KEY_GID to info.gid.toString(),
                    AutomationEvent.KEY_LABEL to (info.label ?: ""),
                )))
                if (downloadManager.getDownloadingCount() == 0 && downloadManager.getWaitingCount() == 0) {
                    postEvent(AutomationEvent(EventTriggerType.ALL_DOWNLOADS_COMPLETED))
                }
            }
        }
        downloadManager.addDownloadListener(listener)
        registered.add(AutoCloseable { downloadManager.removeDownloadListener(listener) })

        val infoListener = object : DownloadManager.DownloadInfoListener {
            override fun onAdd(info: DownloadInfo, list: MutableList<DownloadInfo>, position: Int) {
                postEvent(AutomationEvent(EventTriggerType.DOWNLOAD_ADDED, payload = mapOf(
                    AutomationEvent.KEY_GID to info.gid.toString(),
                    AutomationEvent.KEY_LABEL to (info.label ?: ""),
                )))
            }
            override fun onReplace(newInfo: DownloadInfo, oldInfo: DownloadInfo) {}
            override fun onUpdate(info: DownloadInfo, list: MutableList<DownloadInfo>, waitList: java.util.LinkedList<DownloadInfo>) {}
            override fun onUpdateAll() {}
            override fun onReload() {}
            override fun onChange() {}
            override fun onRenameLabel(from: String, to: String) {}
            override fun onRemove(info: DownloadInfo, list: MutableList<DownloadInfo>, position: Int) {}
            override fun onUpdateLabels() {}
        }
        downloadManager.addDownloadInfoListener(infoListener)
        registered.add(AutoCloseable { downloadManager.removeDownloadInfoListener(infoListener) })
    }

    private fun registerNetworkListener() {
        val listener = object : NetworkStateManager.Listener {
            override fun onNetworkStateChanged(newState: NetworkStateManager.State) {
                when (newState) {
                    NetworkStateManager.State.ONLINE_WIFI -> postEvent(AutomationEvent(EventTriggerType.WIFI_CONNECTED))
                    NetworkStateManager.State.ONLINE_METERED -> postEvent(AutomationEvent(EventTriggerType.NETWORK_AVAILABLE))
                    NetworkStateManager.State.OFFLINE -> postEvent(AutomationEvent(EventTriggerType.NETWORK_LOST))
                    NetworkStateManager.State.TRANSITIONING -> {}
                }
            }
            override fun onNetworkLost() {
                postEvent(AutomationEvent(EventTriggerType.NETWORK_LOST))
            }
            override fun onNetworkRecovered() {
                postEvent(AutomationEvent(EventTriggerType.NETWORK_AVAILABLE))
            }
        }
        networkStateManager.addListener(listener)
        registered.add(AutoCloseable { networkStateManager.removeListener(listener) })
    }

    private fun registerFavouriteListener() {
        val listener = object : FavouriteStatusRouter.Listener {
            override fun onModifyFavourites(gid: Long, slot: Int) {
                val type = if (slot == 0) EventTriggerType.FAVORITE_REMOVED else EventTriggerType.FAVORITE_ADDED
                postEvent(AutomationEvent(type, payload = mapOf(AutomationEvent.KEY_GID to gid.toString())))
            }
        }
        favouriteRouter.addListener(listener)
        registered.add(AutoCloseable { favouriteRouter.removeListener(listener) })

        EhDB.addHistoryListener { gid ->
            postEvent(AutomationEvent(EventTriggerType.HISTORY_ADDED, payload = mapOf(
                AutomationEvent.KEY_GID to gid.toString(),
            )))
        }
    }

    private fun registerLocalGalleryListener() {
        val listener = object : LocalGalleryManager.LocalGalleryListener {
            override fun onScanStart() {}
            override fun onScanProgress(current: String?) {}
            override fun onScanComplete(localGalleries: MutableList<LocalGalleryInfo>, recycleBinGalleries: MutableList<LocalGalleryInfo>) {
                postEvent(AutomationEvent(EventTriggerType.LOCAL_GALLERY_ADDED, payload = mapOf(
                    "count" to localGalleries.size.toString(),
                )))
            }
            override fun onGalleryDeleted(gallery: LocalGalleryInfo, success: Boolean) {}
            override fun onGalleryRestored(gallery: LocalGalleryInfo, success: Boolean) {}
        }
        localGalleryManager.addListener(listener)
        registered.add(AutoCloseable { localGalleryManager.removeListener(listener) })
    }

    private fun registerProcessLifecycle() {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                postEvent(AutomationEvent(EventTriggerType.APP_FOREGROUND))
            }
            override fun onStop(owner: LifecycleOwner) {
                postEvent(AutomationEvent(EventTriggerType.APP_BACKGROUND))
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        registered.add(AutoCloseable { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) })
    }

    private fun registerBatteryReceiver() {
        val receiver = object : BroadcastReceiver() {
            private var charging = false
            override fun onReceive(c: Context?, intent: Intent?) {
                intent ?: return
                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        val nowCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL
                        if (nowCharging != charging) {
                            charging = nowCharging
                            postEvent(AutomationEvent(
                                if (nowCharging) EventTriggerType.CHARGING_STARTED else EventTriggerType.CHARGING_STOPPED,
                            ))
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        batteryReceiver = receiver
    }
}