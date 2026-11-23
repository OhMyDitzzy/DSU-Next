package com.ditzzy.dsunext.util

import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.function.Consumer

/**
 * Runs shell commands using the root shell when available, or a plain shell otherwise.
 */
class CmdRunner {
    private constructor() {

    }

    companion object {
        @Volatile
        private var process: Process? = null

        @JvmStatic
        fun run(cmd: String): String {
            if (Shell.getShell().isRoot()) {
                val `out`: List<String> = Shell.cmd(cmd).exec().getOut()
                return java.lang.String.join("\n", `out`)
            }
            val output: StringBuilder = StringBuilder()
            runCommand(cmd, Consumer { line ->
                output.append(line).append('\n')
            })
            return output.toString()
        }

        /**
         * Streams every line of output to `onReceive`. On the root shell this returns
         * immediately, otherwise it blocks until the command finishes or [#destroy()] is called.
         */
        @JvmStatic
        fun runReadEachLine(cmd: String, onReceive: Consumer<String>) {
            if (Shell.getShell().isRoot()) {
                // Lines are delivered on the main thread, the default executor of CallbackList
                val callbackList: CallbackList<String> = object : CallbackList<String>() {
                    override fun onAddElement(line: String) {
                        onReceive.accept(line)
                    }
                }
                Shell.cmd(cmd).to(callbackList).submit()
                return
            }
            runCommand(cmd, onReceive)
        }

        private fun runCommand(cmd: String, onReceive: Consumer<String>) {
            try {
                val started: Process = ProcessBuilder("/bin/sh", "-c", cmd).start()
                process = started
                BufferedReader(InputStreamReader(started.getInputStream())).use { reader ->
                    var line: String? = null
                    while (((reader.readLine()).also { line = it } != null)) {
                        if (!line!!.isEmpty()) {
                            onReceive.accept(line)
                        }
                    }
                }
            } catch (ignored: IOException) {
                // The process can be destroyed while its output is being read.
            }
        }

        @JvmStatic
        fun destroy() {
            if (Shell.getShell().isRoot()) {
                // The only way to stop a running command in the shared shell is to recycle it
                try {
                    Shell.getShell().close()
                } catch (ignored: IOException) {

                }
                Shell.getShell({ shell ->

                })
                return
            }
            val current: Process? = process
            if ((current != null)) {
                current!!.destroy()
                process = null
            }
        }
    }
}
