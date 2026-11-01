package com.rout.air.data

import kotlinx.coroutines.flow.MutableSharedFlow

/** Event ringan antar komponen (mis. overlay ingin membuka tab Studio). */
object UiEvents {
    val openStudio = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}
