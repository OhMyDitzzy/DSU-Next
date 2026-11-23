package com.ditzzy.dsunext.viewmodel

import android.app.Application
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.ditzzy.dsunext.R
import com.ditzzy.dsunext.service.WorkspaceService
import com.ditzzy.dsunext.workspace.WorkspaceJobs
import com.ditzzy.dsunext.model.Workspace
import com.ditzzy.dsunext.model.WorkspaceInfo
import com.ditzzy.dsunext.ui.Event
import com.ditzzy.dsunext.ui.UiMessage
import com.ditzzy.dsunext.workspace.WorkspaceNames
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.ArrayList
import java.util.Collections
import java.util.concurrent.ExecutorService

class EditGsiViewModel : AndroidViewModel {
    enum class Access {
        CHECKING,
        SERVICE_ERROR,
        READY;

    }

    /** What the progress dialog shows. Immutable, every change is a new instance. */
    class Operation {
        enum class Type {
            IMPORT,
            REPACK,
            EXPORT,
            DELETE;

        }

        enum class Status {
            RUNNING,
            DONE,
            FAILED;

        }

        val type: Type

        val workspace: String

        val status: Status

        /** 0-100, or -1 when there is nothing to measure. */
        val percent: Int

        val log: String

        /** The reason of a failure. */
        val message: String

        /** Result of a repack: the image that was written. */
        val file: String

        val size: Long

        val newEntries: Int

        private constructor(type: Type, workspace: String, status: Status, percent: Int, log: String, message: String, file: String, size: Long, newEntries: Int) {
            this.type = type
            this.workspace = workspace
            this.status = status
            this.percent = percent
            this.log = log
            this.message = message
            this.file = file
            this.size = size
            this.newEntries = newEntries
        }

        fun withPercent(next: Int): Operation {
            return Operation(type, workspace, status, next, log, message, file, size, newEntries)
        }

        fun withLog(line: String): Operation {
            var next: String = (if (log.isEmpty()) line else ((log + "\n") + line))
            if ((next.length > MAX_LOG_CHARS)) {
                next = next.substring((next.length - MAX_LOG_CHARS))
            }
            return Operation(type, workspace, status, percent, next, message, file, size, newEntries)
        }

        fun done(resultFile: String, resultSize: Long, resultNewEntries: Int): Operation {
            return Operation(type, workspace, Status.DONE, 100, log, "", resultFile, resultSize, resultNewEntries)
        }

        fun failed(reason: String): Operation {
            return Operation(type, workspace, Status.FAILED, percent, log, reason, file, size, newEntries)
        }

        companion object {
            @JvmStatic
            fun running(type: Type, workspace: String): Operation {
                return Operation(type, workspace, Status.RUNNING, -1, "", "", "", 0L, 0)
            }
        }
    }

    private val access: MutableLiveData<Access> = MutableLiveData<Access>(Access.CHECKING)

    private val accessDetail: MutableLiveData<String> = MutableLiveData<String>("")

    private val workspaces: MutableLiveData<List<Workspace>> = MutableLiveData<List<Workspace>>(Collections.emptyList())

    private val operation: MutableLiveData<Operation> = MutableLiveData<Operation>()

    private val messages: MutableLiveData<Event<UiMessage>> = MutableLiveData<Event<UiMessage>>()

    private val info: MutableLiveData<Event<WorkspaceInfo>> = MutableLiveData<Event<WorkspaceInfo>>()

    private val io: ExecutorService = WorkspaceJobs.executor()

    private val operationLock: Any = Any()

    private val service: WorkspaceService

    private var current: Operation? = null

    private var started: Boolean = false

    private var warningAccepted: Boolean = false

    private val callback: WorkspaceService.Listener = object : WorkspaceService.Listener {
        override fun onLog(line: String) {
            var next: Operation? = null
            synchronized(operationLock) {
                if (((current == null) || (current!!.status != Operation.Status.RUNNING))) {
                    return
                }
                current = current!!.withLog(line)
                next = current
            }
            operation.postValue(next)
        }

        override fun onProgress(percent: Int) {
            var next: Operation? = null
            synchronized(operationLock) {
                if (((current == null) || (current!!.status != Operation.Status.RUNNING))) {
                    return
                }
                current = current!!.withPercent(percent)
                next = current
            }
            operation.postValue(next)
        }

        override fun onFinished(resultJson: String) {
            onJobFinished(resultJson)
        }

        override fun onError(message: String) {
            failJob(message)
        }
    }

    constructor(application: Application) : super(application) {

        service = WorkspaceService(application)
    }

    fun getAccess(): LiveData<Access> {
        return access
    }

    fun getAccessDetail(): LiveData<String> {
        return accessDetail
    }

    fun getWorkspaces(): LiveData<List<Workspace>> {
        return workspaces
    }

    fun getOperation(): LiveData<Operation> {
        return operation
    }

    fun getMessages(): LiveData<Event<UiMessage>> {
        return messages
    }

    fun getInfo(): LiveData<Event<WorkspaceInfo>> {
        return info
    }

    fun isWarningAccepted(): Boolean {
        return warningAccepted
    }

    fun acceptWarning() {
        warningAccepted = true
    }

    fun start() {
        if (started) {
            return
        }
        started = true
        checkAccess()
    }

    fun checkAccess() {
        access.setValue(Access.CHECKING)
        verifyService()
    }

    private fun verifyService() {
        io.execute(callback1@ {
            val s: WorkspaceService? = service
            if ((s == null)) {
                return@callback1
            }
            try {
                val error: String? = s!!.getNativeError()
                if (((error != null) && !error!!.isEmpty())) {
                    accessDetail.postValue(error)
                    access.postValue(Access.SERVICE_ERROR)
                    return@callback1
                }
                access.postValue(Access.READY)
                loadWorkspaces(s)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to open workspace storage.", e)
                accessDetail.postValue(java.lang.String.valueOf(e.message))
                access.postValue(Access.SERVICE_ERROR)
            }
        })
    }

    fun refresh() {
        io.execute({
            loadWorkspaces(service)
        })
    }

    private fun loadWorkspaces(s: WorkspaceService?) {
        if ((s == null)) {
            return
        }
        try {
            workspaces.postValue(Workspace.parseList(s!!.listWorkspaces()))
        } catch (e: Exception) {
            Log.e(TAG, "Unable to read the workspace list.", e)
            postMessage(UiMessage(R.string.gsi_list_failed))
        }
    }

    fun isNameTaken(name: String): Boolean {
        for (workspace in currentNames()) {
            if (workspace.getName().equals(name)) {
                return true
            }
        }
        return false
    }

    /** `base` with a number added when a workspace of that name already exists. */
    fun uniqueName(base: String): String {
        val taken: MutableList<String> = ArrayList<String>()
        for (workspace in currentNames()) {
            taken.add(workspace.getName())
        }
        return WorkspaceNames.unique(base, taken)
    }

    private fun currentNames(): List<Workspace> {
        val list: List<Workspace>? = workspaces.getValue()
        return (if ((list == null)) Collections.emptyList() else list!!)
    }

    fun loadInfo(name: String) {
        io.execute(callback3@ {
            val s: WorkspaceService? = service
            if ((s == null)) {
                postMessage(UiMessage(R.string.gsi_service_unavailable))
                return@callback3
            }
            try {
                val json: String? = s!!.getWorkspaceInfo(name)
                if (((json == null) || json!!.isEmpty())) {
                    postMessage(UiMessage(R.string.gsi_info_failed))
                    return@callback3
                }
                info.postValue(Event<WorkspaceInfo>(WorkspaceInfo.parse(json!!)))
            } catch (e: Exception) {
                Log.e(TAG, "Unable to read the workspace details.", e)
                postMessage(UiMessage(R.string.gsi_info_failed))
            }
        })
    }

    fun importFile(uri: Uri, sourceName: String, name: String) {
        if (!begin(Operation.Type.IMPORT, name)) {
            return
        }
        io.execute(callback4@ {
            val s: WorkspaceService? = service
            if ((s == null)) {
                failJob(text(R.string.gsi_service_unavailable))
                return@callback4
            }
            try {
                getApplication<Application>().getContentResolver().openFileDescriptor(uri, "r").use { pfd ->
                    if ((pfd == null)) {
                        failJob(text(R.string.gsi_open_file_failed))
                        return@callback4
                    }
                    s!!.importWorkspace(pfd, sourceName, name, callback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to start the import.", e)
                failJob(describe(e))
            }
        })
    }

    /** @param size null for the original size, "auto", or bytes with an optional K, M or G. */
    fun repack(name: String, size: String?, outputName: String) {
        if (!begin(Operation.Type.REPACK, name)) {
            return
        }
        io.execute(callback5@ {
            val s: WorkspaceService? = service
            if ((s == null)) {
                failJob(text(R.string.gsi_service_unavailable))
                return@callback5
            }
            try {
                s!!.repackWorkspace(name, size, outputName, callback)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to start the repack.", e)
                failJob(describe(e))
            }
        })
    }

    /** Copies a repacked image to the file the user chose. */
    fun export(name: String, fileName: String, target: Uri) {
        if (!begin(Operation.Type.EXPORT, name)) {
            return
        }
        io.execute(callback6@ {
            val s: WorkspaceService? = service
            if ((s == null)) {
                failJob(text(R.string.gsi_service_unavailable))
                return@callback6
            }
            try {
                getApplication<Application>().getContentResolver().openFileDescriptor(target, "wt").use { pfd ->
                    if ((pfd == null)) {
                        failJob(text(R.string.gsi_open_file_failed))
                        return@callback6
                    }
                    s!!.exportOutput(name, fileName, pfd, callback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to start saving the image.", e)
                failJob(describe(e))
            }
        })
    }

    fun delete(name: String) {
        if (!begin(Operation.Type.DELETE, name)) {
            return
        }
        io.execute(callback7@ {
            val s: WorkspaceService? = service
            if ((s == null)) {
                failJob(text(R.string.gsi_service_unavailable))
                return@callback7
            }
            try {
                val deleted: Boolean = s!!.deleteWorkspace(name)
                loadWorkspaces(s)
                if (deleted) {
                    finishQuietly(UiMessage(R.string.gsi_deleted, name))
                } else {
                    failJob(getApplication<Application>().getString(R.string.gsi_delete_failed, name))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to delete the workspace.", e)
                failJob(describe(e))
            }
        })
    }

    /** Closes the dialog of a finished or failed job. */
    fun dismissOperation() {
        synchronized(operationLock) {
            if (((current != null) && (current!!.status == Operation.Status.RUNNING))) {
                return
            }
            current = null
        }
        operation.postValue(null)
    }

    private fun publish(next: Operation) {
        synchronized(operationLock) {
            current = next
        }
        operation.postValue(next)
    }

    private fun onJobFinished(resultJson: String) {
        var finished: Operation? = null
        synchronized(operationLock) {
            if ((current == null)) {
                return
            }
            finished = current
        }
        when (finished!!.type) {
            Operation.Type.REPACK -> {
                var file: String = ""
                var size: Long = 0L
                var newEntries: Int = 0
                try {
                    val result: JSONObject = JSONObject(resultJson)
                    file = result.optString("file", "")
                    size = result.optLong("size", 0L)
                    newEntries = result.optInt("new_entries", 0)
                } catch (e: JSONException) {
                    Log.w(TAG, "Unreadable repack result.", e)
                }
                publish(finished!!.done(file, size, newEntries))
            }
            Operation.Type.IMPORT -> {
                finishQuietly(UiMessage(R.string.gsi_imported, finished!!.workspace))
                refresh()
            }
            Operation.Type.EXPORT -> {
                finishQuietly(UiMessage(R.string.gsi_saved))
            }
            else -> {
                finishQuietly(null)
            }
        }
    }

    private fun failJob(reason: String) {
        var base: Operation? = null
        synchronized(operationLock) {
            if ((current == null)) {
                return
            }
            base = current
        }
        publish(base!!.failed(reason))
        // An import that failed leaves nothing behind, but the list may have changed anyway
        refresh()
    }

    /** Ends a job that has nothing more to show than a toast. */
    private fun finishQuietly(message: UiMessage?) {
        synchronized(operationLock) {
            current = null
        }
        operation.postValue(null)
        if ((message != null)) {
            postMessage(message)
        }
    }

    private fun postMessage(message: UiMessage?) {
        messages.postValue(Event<UiMessage>(message!!))
    }

    private fun text(resId: Int): String {
        return getApplication<Application>().getString(resId)
    }

    private fun begin(type: Operation.Type, name: String): Boolean {
        synchronized(operationLock) {
            if (((current != null) && (current!!.status == Operation.Status.RUNNING))) {
                return false
            }
            current = Operation.running(type, name)
            operation.postValue(current)
            return true
        }
    }

    companion object {
        private const val TAG: String = "EditGsiViewModel"

        private const val MAX_LOG_CHARS: Int = (48 * 1024)

        private fun describe(e: Exception): String {
            val message: String? = e.message
            return (if (((message == null) || message!!.isEmpty())) e.javaClass.getSimpleName() else message!!)
        }
    }
}
