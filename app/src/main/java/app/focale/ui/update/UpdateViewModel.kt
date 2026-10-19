package app.focale.ui.update

import androidx.lifecycle.ViewModel
import app.focale.update.AppUpdate
import app.focale.update.UpdateRepository
import app.focale.update.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Accès de l'interface aux mises à jour (pastille, bannière de l'onglet Plus, réglages). */
@HiltViewModel
class UpdateViewModel @Inject constructor(private val repository: UpdateRepository) : ViewModel() {
    val supported: Boolean = repository.supported
    val state: StateFlow<UpdateState> = repository.state

    fun check() = repository.check()
    fun install(update: AppUpdate) = repository.install(update)
}
