package dev.smantics.scribe.ime

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Makes Compose usable inside an [android.inputmethodservice.InputMethodService].
 *
 * An Activity supplies a lifecycle, a ViewModelStore and a SavedStateRegistry to every view
 * it hosts. An input method does not, and Compose refuses to compose without them — the
 * failure is a bare "ViewTreeLifecycleOwner not found" at the moment the keyboard first
 * opens, which is both fatal and easy to mistake for a Compose bug.
 *
 * This class supplies all three and drives the lifecycle from the IME's own callbacks.
 * The store is cleared on destroy so a keyboard that is opened and closed hundreds of
 * times a day does not accumulate anything.
 */
class ImeComposeHost : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private var restored = false

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    /** Call from `onCreate`. */
    fun onCreate() {
        if (!restored) {
            savedStateController.performRestore(null)
            restored = true
        }
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    /** Call from `onStartInputView` — the panel is on screen and interactive. */
    fun onResume() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** Call from `onFinishInputView` — the panel is hidden but the service lives on. */
    fun onPause() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    /** Call from `onDestroy`. */
    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }

    /** Attach the three owners to the root view Compose will be hosted in. */
    fun attachTo(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }
}
