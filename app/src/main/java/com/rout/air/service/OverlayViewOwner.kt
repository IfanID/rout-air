package com.rout.air.service

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/** Lifecycle minimal agar ComposeView bisa dipakai di dalam Service. */
class OverlayViewOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    @Volatile
    private var attached = false

    fun attach() {
        if (attached) return
        attached = true
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    /**
     * Aman dipanggil walau [attach] belum pernah berjalan: LifecycleRegistry
     * menolak pindah dari INITIALIZED langsung ke DESTROYED dan melempar
     * IllegalStateException, yang membuat service crash saat dihentikan
     * sebelum overlay sempat dibuat (mis. izin overlay dicabut).
     */
    fun destroy() {
        if (!attached) return
        attached = false
        runCatching { registry.currentState = Lifecycle.State.DESTROYED }
    }
}
