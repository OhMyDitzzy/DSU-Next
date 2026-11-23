package com.ditzzy.dsunext.service

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.ditzzy.dsunext.workspace.ImageImporter
import com.ditzzy.dsunext.workspace.ImageRepacker
import com.ditzzy.dsunext.workspace.JobReporter
import com.ditzzy.dsunext.workspace.OutputExporter
import com.ditzzy.dsunext.workspace.WorkspaceJobs
import com.ditzzy.dsunext.workspace.WorkspaceStore
import java.io.IOException

class WorkspaceService {
    interface Listener {
        fun onLog(line: String)

        fun onProgress(percent: Int)

        fun onFinished(result: String)

        fun onError(message: String)
    }

    private fun interface Job {
        @Throws(Exception::class)
        fun run(): String
    }

    constructor(context: Context) {
        WorkspaceStore.initialize(context)
    }

    @Throws(IOException::class)
    fun getNativeError(): String {
        if ((!WorkspaceStore.baseDir().isDirectory() && !WorkspaceStore.baseDir().mkdirs())) {
            throw IOException("Cannot create workspace storage")
        }
        return WorkspaceStore.nativeError()
    }

    @Throws(Exception::class)
    fun listWorkspaces(): String {
        return WorkspaceStore.listJson()
    }

    @Throws(Exception::class)
    fun getWorkspaceInfo(name: String): String {
        return WorkspaceStore.infoJson(name)
    }

    @Throws(IOException::class)
    fun deleteWorkspace(name: String): Boolean {
        return WorkspaceStore.delete(name)
    }

    @Throws(IOException::class)
    fun importWorkspace(source: ParcelFileDescriptor, sourceName: String, name: String, listener: Listener) {
        // The picker descriptor can close as soon as this call returns; the queued job owns a duplicate.
        val owned: ParcelFileDescriptor = ParcelFileDescriptor.dup(source.getFileDescriptor())
        enqueue(owned, listener, {
            ImageImporter.run(owned, sourceName, name, reporter(listener))
        })
    }

    fun repackWorkspace(name: String, size: String?, outputName: String, listener: Listener) {
        enqueue(null, listener, {
            ImageRepacker.run(name, size, outputName, reporter(listener))
        })
    }

    @Throws(IOException::class)
    fun exportOutput(name: String, fileName: String, target: ParcelFileDescriptor, listener: Listener) {
        val owned: ParcelFileDescriptor = ParcelFileDescriptor.dup(target.getFileDescriptor())
        enqueue(owned, listener, {
            OutputExporter.run(name, fileName, owned, reporter(listener))
        })
    }

    private fun enqueue(owned: ParcelFileDescriptor?, listener: Listener, job: Job) {
        WorkspaceJobs.executor().execute({
            try {
                listener.onFinished(job.run())
            } catch (error: Exception) {
                Log.e(TAG, "Workspace job failed", error)
                listener.onError((if ((error.message == null)) error.javaClass.getSimpleName() else error.message!!))
            } finally {
                if ((owned != null)) {
                    try {
                        owned!!.close()
                    } catch (ignored: IOException) {

                    }
                }
            }
        })
    }

    companion object {
        private const val TAG: String = "WorkspaceService"

        // Import/export normally closes the descriptor through its stream.
        private fun reporter(listener: Listener): JobReporter {
            return object : JobReporter {
                override fun log(line: String) {
                    listener.onLog(line)
                }

                override fun progress(percent: Int) {
                    listener.onProgress(percent)
                }
            }
        }
    }
}
