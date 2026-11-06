package com.rout.air.encode.benchmark

import java.util.concurrent.atomic.AtomicBoolean

/** Thread pengisi CPU, meniru logika game di inti yang tidak dipakai render. */
internal class CpuLoad(private val threads: Int) {
    private val stop = AtomicBoolean(false)

    fun start() {
        repeat(threads) {
            Thread {
                var value = 1.0
                while (!stop.get()) {
                    for (index in 0 until 100_000) {
                        value += Math.sin(index.toDouble()) * Math.cos(value)
                    }
                    if (!value.isFinite()) value = 1.0
                }
            }.apply {
                isDaemon = true
                name = "rout-bench-cpu"
                start()
            }
        }
    }

    fun stop() {
        stop.set(true)
    }
}
