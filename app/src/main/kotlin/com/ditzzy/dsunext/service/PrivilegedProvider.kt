package com.ditzzy.dsunext.service

import android.util.Log
import com.ditzzy.dsunext.IPrivilegedService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Single access point to the [IPrivilegedService], no matter how it got bound
 * (Shizuku, root or as a system app).
 */
class PrivilegedProvider {
    private constructor() {

    }

    fun interface ServiceAction {
        @Throws(Exception::class)
        fun run(service: IPrivilegedService)
    }

    companion object {
        private const val TAG: String = "PrivilegedProvider"

        private const val TIMEOUT_MS: Long = 20000L

        private const val POLL_INTERVAL_MS: Long = 250L

        private val CONNECTION: Connection = Connection()

        private val EXECUTOR: ExecutorService = Executors.newCachedThreadPool()

        @JvmStatic
        fun getConnection(): Connection {
            return CONNECTION
        }

        @JvmStatic
        fun isConnected(): Boolean {
            return (CONNECTION.getService() != null)
        }

        @JvmStatic
        fun run(action: ServiceAction) {
            run(action, null)
        }

        /**
         * Runs `action` in a background thread as soon as the service is available.
         *
         * @param onFail called when the service did not show up in time, or when the action threw.
         *               Without it a failure would only be logged, leaving the UI waiting forever.
         */
        @JvmStatic
        fun run(action: ServiceAction, onFail: Runnable?) {
            EXECUTOR.execute(callback1@ {
                val service: IPrivilegedService? = awaitService()
                if ((service == null)) {
                    Log.e(TAG, "Service unavailable.")
                    if ((onFail != null)) {
                        onFail!!.run()
                    }
                    return@callback1
                }
                try {
                    action!!.run(service)
                } catch (e: Exception) {
                    Log.e(TAG, "Privileged action failed.", e)
                    if ((onFail != null)) {
                        onFail!!.run()
                    }
                }
            })
        }

        /**
         * Blocking variant, must never be called from the main thread.
         *
         * @throws IllegalStateException when the service is not available after the timeout.
         */
        @JvmStatic
        fun getService(): IPrivilegedService {
            val service: IPrivilegedService? = awaitService()
            if ((service == null)) {
                throw IllegalStateException("Service unavailable.")
            }
            return service!!
        }

        private fun awaitService(): IPrivilegedService? {
            var waited: Long = 0L
            var service: IPrivilegedService? = CONNECTION.getService()
            while ((service == null)) {
                if ((waited >= TIMEOUT_MS)) {
                    return null
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
                waited += POLL_INTERVAL_MS
                service = CONNECTION.getService()
            }
            return service
        }
    }
}
