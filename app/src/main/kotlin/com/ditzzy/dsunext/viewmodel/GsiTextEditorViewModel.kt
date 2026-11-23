package com.ditzzy.dsunext.viewmodel

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.ditzzy.dsunext.R
import com.ditzzy.dsunext.filemanager.api.*
import com.ditzzy.dsunext.ui.Event
import com.ditzzy.dsunext.ui.editor.EditorDocument
import com.ditzzy.dsunext.workspace.GsiFileEditor
import com.ditzzy.dsunext.workspace.WorkspaceJobs
import com.ditzzy.dsunext.workspace.WorkspaceStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class GsiTextEditorViewModel : AndroidViewModel {
    private val state: MutableLiveData<Int> = MutableLiveData<Int>(0)

    private val errors: MutableLiveData<Event<String>> = MutableLiveData<Event<String>>()

    private val saved: MutableLiveData<Event<Boolean>> = MutableLiveData<Event<Boolean>>()

    private val main: Handler = Handler(Looper.getMainLooper())

    private val cancellation: CancellationToken = CancellationToken()

    private var workspace: String? = null

    private var path: String? = null

    private var loading: Boolean = false

    private var saving: Boolean = false

    @Volatile
    private var cleared: Boolean = false

    private var document: EditorDocument? = null

    constructor(application: Application) : super(application) {

        WorkspaceStore.initialize(application)
    }

    fun state(): LiveData<Int> {
        return state
    }

    fun errors(): LiveData<Event<String>> {
        return errors
    }

    fun saved(): LiveData<Event<Boolean>> {
        return saved
    }

    fun document(): EditorDocument? {
        return document
    }

    fun loading(): Boolean {
        return loading
    }

    fun saving(): Boolean {
        return saving
    }

    fun changed() {
        state.setValue((state.getValue()!! + 1))
    }

    fun start(workspace: String, path: String) {
        if ((this.path != null)) {
            return
        }
        this.workspace = workspace
        this.path = path
        load()
    }

    fun load() {
        if ((loading || (document != null))) {
            return
        }
        loading = true
        changed()
        WorkspaceJobs.executor().execute({
            var contents: String? = null
            var error: String? = null
            try {
                GsiFileEditor(workspace!!).use { editor ->
                    val bytes: ByteArrayOutputStream = object : ByteArrayOutputStream() {
                        @Synchronized
                        override fun write(`data`: ByteArray, offset: Int, length: Int) {
                            if (((count + length) > MAX_BYTES)) {
                                throw IllegalArgumentException(getApplication<Application>().getString(R.string.fm_text_limit))
                            }
                            super.write(`data`, offset, length)
                        }
                    }
                    val result: FileResult = editor.execute(FileRequest.ReadFile(path!!, OutputTarget {
                        bytes
                    }), cancellation, null)
                    requireSuccess(result)
                    contents = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
                    if ((contents!!.indexOf('\u0000') >= 0)) {
                        throw IOException(getApplication<Application>().getString(R.string.fm_text_binary))
                    }
                }
            } catch (failure: Exception) {
                error = describe(failure)
            }
            val loaded: String? = contents
            val failure: String? = error
            main.post(callback4@ {
                if (cleared) {
                    return@callback4
                }
                loading = false
                if ((failure == null)) {
                    document = EditorDocument(loaded!!)
                }
                changed()
                if ((failure != null)) {
                    errors.setValue(Event<String>(failure!!))
                }
            })
        })
    }

    fun save() {
        if (((saving || (document == null)) || !document!!.dirty())) {
            return
        }
        val snapshot: String = document!!.text()
        val bytes: ByteArray = snapshot.toByteArray(StandardCharsets.UTF_8)
        if ((bytes.size > MAX_BYTES)) {
            errors.setValue(Event<String>(getApplication<Application>().getString(R.string.fm_text_limit)))
            return
        }
        saving = true
        changed()
        WorkspaceJobs.executor().execute({
            var error: String? = null
            try {
                GsiFileEditor(workspace!!).use { editor ->
                    val result: FileResult = editor.execute(FileRequest.WriteFile(path!!, InputSource {
                        ByteArrayInputStream(bytes)
                    }), cancellation, null)
                    requireSuccess(result)
                }
            } catch (failure: Exception) {
                error = describe(failure)
            }
            val failure: String? = error
            main.post(callback8@ {
                if (cleared) {
                    return@callback8
                }
                saving = false
                if ((failure == null)) {
                    document!!.markSaved(snapshot)
                }
                changed()
                if ((failure != null)) {
                    errors.setValue(Event<String>(failure!!))
                } else {
                    saved.setValue(Event<Boolean>(true))
                }
            })
        })
    }

    @Throws(IOException::class)
    private fun requireSuccess(result: FileResult) {
        if ((result is FileResult.Success)) {
            return
        }
        if ((result is FileResult.Failure)) {
            throw IOException((result as FileResult.Failure).error.message)
        }
        if ((result is FileResult.Partial)) {
            throw IOException((result as FileResult.Partial).error.message)
        }
        throw IOException(getApplication<Application>().getString(R.string.fm_editor_cancelled))
    }

    private fun describe(failure: Exception): String {
        if ((failure is java.nio.charset.CharacterCodingException)) {
            return getApplication<Application>().getString(R.string.fm_text_binary)
        }
        return (if ((failure.message == null)) getApplication<Application>().getString(R.string.fm_failed) else failure.message!!)
    }

    protected override fun onCleared() {
        cleared = true
        cancellation.cancel()
    }

    companion object {
        const val MAX_BYTES: Int = (256 * 1024)
    }
}
