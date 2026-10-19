package app.focale.core.mediastore

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.focale.core.di.ApplicationScope
import app.focale.core.permissions.MediaAccess
import app.focale.core.permissions.MediaAccessManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Observe MediaStore tant que l'application est au premier plan et déclenche une
 * synchronisation (avec anti-rebond) à chaque changement.
 */
@Singleton
class MediaChangeObserver @Inject constructor(
    private val resolver: ContentResolver,
    private val syncManager: MediaSyncManager,
    private val accessManager: MediaAccessManager,
    @ApplicationScope private val scope: CoroutineScope,
) : DefaultLifecycleObserver {

    private val changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            changes.tryEmit(Unit)
        }
    }

    private var registered = false

    init {
        scope.launch {
            changes.debounce(DEBOUNCE_MS).collect { syncManager.sync() }
        }
        // Première indexation dès que l'utilisateur accorde l'accès (complet ou partiel).
        scope.launch {
            accessManager.access.drop(1).collect { access ->
                if (access != MediaAccess.DENIED) syncManager.sync()
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        accessManager.refresh()
        if (!registered) {
            resolver.registerContentObserver(MediaStoreSource.FILES_URI, true, observer)
            registered = true
        }
        // Rattrape les changements survenus pendant que l'application était en arrière-plan.
        syncManager.requestSync()
    }

    override fun onStop(owner: LifecycleOwner) {
        if (registered) {
            resolver.unregisterContentObserver(observer)
            registered = false
        }
    }

    /** Signale un changement provoqué par l'application elle-même. */
    fun notifyChanged() {
        changes.tryEmit(Unit)
    }

    private companion object {
        const val DEBOUNCE_MS = 800L
    }
}
