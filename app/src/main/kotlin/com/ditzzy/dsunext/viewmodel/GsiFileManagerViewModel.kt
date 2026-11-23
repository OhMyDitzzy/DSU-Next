package com.ditzzy.dsunext.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.ditzzy.dsunext.filemanager.api.*
import com.ditzzy.dsunext.ui.Event
import com.ditzzy.dsunext.workspace.GsiFileEditor
import com.ditzzy.dsunext.workspace.WorkspaceJobs
import com.ditzzy.dsunext.workspace.WorkspaceStore
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections

class GsiFileManagerViewModel : AndroidViewModel {
    class Outcome {
        val request: FileRequest

        val result: FileResult?

        val error: String?

        constructor(request: FileRequest, result: FileResult?, error: String?) {
            this.request = request
            this.result = result
            this.error = error
        }
    }

    private val entries: MutableLiveData<List<FileEntry>> = MutableLiveData<List<FileEntry>>(Collections.emptyList())

    private val directory: MutableLiveData<String> = MutableLiveData<String>("")

    private val busy: MutableLiveData<Boolean> = MutableLiveData<Boolean>(false)

    private val progress: MutableLiveData<OperationProgress> = MutableLiveData<OperationProgress>()

    private val outcomes: MutableLiveData<Event<Outcome>> = MutableLiveData<Event<Outcome>>()

    private val clipboardLabel: MutableLiveData<String> = MutableLiveData<String>("")

    @Volatile
    private var editor: GsiFileEditor? = null

    @Volatile
    private var cancellation: CancellationToken? = null

    @Volatile
    private var cleared: Boolean = false

    @Volatile
    private var currentDirectory: String = ""

    @Volatile
    private var clipboard: FileEntry? = null

    private var cut: Boolean = false

    private var workspaceName: String? = null

    private var exportPath: String? = null

    constructor(application: Application) : super(application) {

        WorkspaceStore.initialize(application)
    }

    fun getEntries(): LiveData<List<FileEntry>> {
        return entries
    }

    fun getDirectory(): LiveData<String> {
        return directory
    }

    fun getBusy(): LiveData<Boolean> {
        return busy
    }

    fun getProgress(): LiveData<OperationProgress> {
        return progress
    }

    fun getOutcomes(): LiveData<Event<Outcome>> {
        return outcomes
    }

    fun getClipboardLabel(): LiveData<String> {
        return clipboardLabel
    }

    fun isBusy(): Boolean {
        return java.lang.Boolean.TRUE.equals(busy.getValue())
    }

    fun path(): String {
        return currentDirectory
    }

    fun child(name: String): String {
        return (if (currentDirectory.isEmpty()) name else ((currentDirectory + "/") + name))
    }

    fun start(name: String) {
        if ((workspaceName != null)) {
            return
        }
        workspaceName = name
        execute(FileRequest.ListDirectory(""))
    }

    fun navigate(path: String) {
        execute(FileRequest.ListDirectory(path))
    }

    fun refresh() {
        navigate(currentDirectory)
    }

    fun cancel() {
        val token: CancellationToken? = cancellation
        if ((token != null)) {
            token!!.cancel()
        }
    }

    fun copy(entry: FileEntry, cut: Boolean) {
        if (isBusy()) {
            return
        }
        clipboard = entry
        this.cut = cut
        clipboardLabel.setValue(entry.name)
    }

    fun paste() {
        if ((clipboard == null)) {
            return
        }
        val destination: String = child(clipboard!!.name)
        execute((if (cut) FileRequest.Move(clipboard!!.path, destination) else FileRequest.Copy(clipboard!!.path, destination)))
    }

    fun setExportPath(path: String) {
        exportPath = path
    }

    fun export(uri: Uri) {
        if ((exportPath == null)) {
            return
        }
        execute(FileRequest.ReadFile(exportPath!!, OutputTarget callback1@ {
            val `out`: OutputStream? = getApplication<Application>().getContentResolver().openOutputStream(uri, "wt")
            if ((`out` == null)) {
                throw IOException("Cannot open the selected destination")
            }
            return@callback1 `out`
        }))
    }

    fun importFile(uri: Uri, name: String) {
        execute(FileRequest.CreateFile(child(name), InputSource callback2@ {
            val input: InputStream? = getApplication<Application>().getContentResolver().openInputStream(uri)
            if ((input == null)) {
                throw IOException("Cannot open the selected file")
            }
            return@callback2 input
        }))
    }

    fun clearClipboard() {
        if (isBusy()) {
            return
        }
        clipboard = null
        clipboardLabel.setValue("")
    }

    fun execute(request: FileRequest?) {
        if ((isBusy() || cleared)) {
            return
        }
        busy.setValue(true)
        progress.setValue(null)
        val token: CancellationToken = CancellationToken()
        cancellation = token
        WorkspaceJobs.executor().execute(callback3@ {
            if (cleared) {
                busy.postValue(false)
                return@callback3
            }
            try {
                val session: GsiFileEditor = session()
                val result: FileResult = session.execute(request!!, token, progress::postValue)
                if ((request is FileRequest.ListDirectory)) {
                    if ((result is FileResult.Success)) {
                        currentDirectory = (request as FileRequest.ListDirectory).path
                        directory.postValue(currentDirectory)
                        entries.postValue(((result as FileResult.Success).value as FileValue.Listing).entries)
                    } else {
                        outcomes.postValue(Event<Outcome>(Outcome(request!!, result, null)))
                    }
                } else {
                    if ((((request is FileRequest.Move) && (result is FileResult.Success)) && (((result as FileResult.Success).value as FileValue.Destination).path != null))) {
                        clipboard = null
                        clipboardLabel.postValue("")
                    }
                    try {
                        val listing: FileResult = session.execute(FileRequest.ListDirectory(currentDirectory), CancellationToken(), null)
                        if ((listing is FileResult.Success)) {
                            entries.postValue(((listing as FileResult.Success).value as FileValue.Listing).entries)
                        }
                    } catch (ignored: IOException) {

                    }
                    // The original operation result carries the useful failure details.
                    outcomes.postValue(Event<Outcome>(Outcome(request!!, result, null)))
                }
            } catch (error: Exception) {
                error(request, error)
            } finally {
                busy.postValue(false)
            }
        })
    }

    @Throws(IOException::class)
    private fun session(): GsiFileEditor {
        if ((editor == null)) {
            editor = GsiFileEditor(workspaceName!!)
        }
        return editor!!
    }

    private fun error(request: FileRequest?, error: Exception) {
        outcomes.postValue(Event<Outcome>(Outcome(request!!, null, (if ((error.message == null)) error.javaClass.getSimpleName() else error.message!!))))
    }

    protected override fun onCleared() {
        cleared = true
        cancel()
        WorkspaceJobs.executor().execute({
            if ((editor != null)) {
                editor!!.close()
            }
        })
    }
}
