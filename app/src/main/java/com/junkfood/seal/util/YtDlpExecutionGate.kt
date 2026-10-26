package com.junkfood.seal.util

import java.util.concurrent.locks.ReentrantLock

/** Prevent replacing yt-dlp files while a process is using them. */
internal class YtDlpExecutionGate {
    private val lock = ReentrantLock(true)

    fun <T> execute(checkForUpdate: () -> Unit, block: () -> T): T = run {
        checkForUpdate()
        block()
    }

    fun <T> run(block: () -> T): T {
        lock.lockInterruptibly()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
