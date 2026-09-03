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
 * An Activity gives every view it hosts a lifecycle, a ViewModelStore and a
 * SavedStateRegistry. An input method gives none of them, and Compose will not compose
 * without all three.
 *
 * **The part that is easy to get wrong, and did get wrong here.** Setting the owners on
 * the `ComposeView` is not enough. Compose resolves its window recomposer from
 * `view.rootView` — the decor view of the window the view ends up in — and an input
 * method's decor is created by the framework with no owners on it. The lookup then fails
 * with "ViewTreeLifecycleOwner not found", composition never starts, and the symptom is
 * not an error the user can see: **the keyboard is listed, can be enabled and selected,
 * and simply never draws anything.**
 *
 * So [attachTo] walks up to the root and sets the owners there as well. That is the whole
 * fix, and it is why this class exists rather than three lines at the call site.
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

    /**
     * Call when the surface is on screen.
     *
     * The lifecycle must reach STARTED before Compose produces frames — its frame clock
     * begins paused and is resumed by `ON_START`. A host left at CREATED composes and then
     * draws nothing, which looks exactly like a crash and is harder to diagnose.
     */
    fun onResume() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** Call when the surface is hidden but the service lives on. */
    fun onPause() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    /** Call from `onDestroy`. */
    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }

    /**
     * Attach the owners to [view] **and to the root of the window it lives in**.
     *
     * Both matter. Compose reads the owners from the view for the composition itself, and
     * from the root view for the recomposer that drives it.
     */
    fun attachTo(view: View) {
        view.applyOwners()
        val root = view.rootView
        if (root !== view) root.applyOwners()
    }

    /**
     * The root view of an input method's window only exists once the window does, which
     * can be after `onCreateInputView`. Call this again from `onStartInputView`, when the
     * decor is guaranteed to be there.
     */
    fun attachToWindowRoot(root: View?) {
        root?.applyOwners()
    }

    private fun View.applyOwners() {
        setViewTreeLifecycleOwner(this@ImeComposeHost)
        setViewTreeViewModelStoreOwner(this@ImeComposeHost)
        setViewTreeSavedStateRegistryOwner(this@ImeComposeHost)
    }
}
