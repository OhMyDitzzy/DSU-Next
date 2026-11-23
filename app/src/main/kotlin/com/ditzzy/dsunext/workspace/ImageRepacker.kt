package com.ditzzy.dsunext.workspace

import com.ditzzy.dsunext.yuki.BuildResult
import com.ditzzy.dsunext.yuki.HashtreeFooterResult
import com.ditzzy.dsunext.yuki.Yuki
import com.ditzzy.dsunext.yuki.YukiLogger
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Properties

class ImageRepacker {
    private constructor() {

    }

    companion object {
        /** Hash of the AVB hashtree footer, the same as avbtool's `--hash_algorithm sha256`. */
        private const val AVB_HASH_ALGORITHM: String = "sha256"

        /**
         * @param size null for the original size, "auto", or bytes with an optional K, M or G.
         * @return a JSON object that describes the image that was written.
         */
        @JvmStatic
        @Throws(IOException::class, JSONException::class)
        fun run(name: String, size: String?, outputName: String, reporter: JobReporter): String {
            val nativeError: String = WorkspaceStore.nativeError()
            if (!nativeError.isEmpty()) {
                throw IOException(nativeError)
            }
            val workspace: File = WorkspaceStore.existing(name)
            GsiMetadata.checkHealthy(workspace)
            if (!WorkspaceStore.isReady(workspace)) {
                throw IOException("This workspace is incomplete, delete it and import the image again")
            }
            if (!WorkspaceNames.isValid(outputName)) {
                throw IOException(("Invalid output name: " + outputName))
            }
            if (((size != null) && !WorkspaceNames.isValidSize(size))) {
                throw IOException(("Invalid size: " + size))
            }
            val props: Properties = WorkspaceStore.readProps(workspace)
            val partition: String = props.getProperty(WorkspaceStore.K_PARTITION, WorkspaceStore.PARTITION)
            val outputDir: File = File(workspace, WorkspaceStore.OUTPUT_DIR)
            if ((!outputDir.isDirectory() && !outputDir.mkdirs())) {
                throw IOException(("Cannot create " + outputDir))
            }
            val fileName: String = (outputName + ".img")
            val output: File = File(outputDir, fileName)
            reporter.progress(-1)
            var result: BuildResult? = null
            try {
                result = Yuki.buildImage(workspace, partition, output, size, true, YukiLogger(reporter::log))
            } catch (e: IOException) {
                output.delete()
                if ((size == null)) {
                    reporter.log(("! If the files no longer fit, repack again with the size set to Auto " + "or to something larger"))
                }
                throw e
            } catch (e: RuntimeException) {
                output.delete()
                if ((size == null)) {
                    reporter.log(("! If the files no longer fit, repack again with the size set to Auto " + "or to something larger"))
                }
                throw e
            }
            // A rebuilt image has no AVB footer and does not boot without one, so the job
            // is not done yet: add the hashtree footer first (avbtool add_hashtree_footer
            // --partition_name <partition> --hash_algorithm sha256 --do_not_generate_fec).
            var footer: HashtreeFooterResult? = null
            try {
                reporter.log("- Adding the AVB hashtree footer")
                footer = Yuki.addHashtreeFooter(output, partition, AVB_HASH_ALGORITHM, YukiLogger(reporter::log))
            } catch (e: IOException) {
                output.delete()
                throw e
            } catch (e: RuntimeException) {
                output.delete()
                throw e
            }
            reporter.log(("- AVB footer added, root digest " + footer!!.rootDigest))
            for (warning in result!!.warnings) {
                reporter.log(("! " + warning))
            }
            if (!result!!.newEntries.isEmpty()) {
                reporter.log((("- " + result!!.newEntries.size) + " new file(s) had no metadata and got default permissions and labels"))
            }
            val length: Long = (output.length()).toLong()
            props.setProperty(WorkspaceStore.K_REPACK_FILE, fileName)
            props.setProperty(WorkspaceStore.K_REPACK_SIZE, java.lang.Long.toString(length))
            props.setProperty(WorkspaceStore.K_REPACK_AT, java.lang.Long.toString(System.currentTimeMillis()))
            WorkspaceStore.writeProps(workspace, props)
            reporter.progress(100)
            val warnings: JSONArray = JSONArray()
            for (warning in result!!.warnings) {
                warnings.put(warning)
            }
            return JSONObject().put("name", name).put("file", fileName).put("size", length).put("new_entries", result!!.newEntries.size).put("removed", result!!.removedEntries).put("warnings", warnings).toString()
        }
    }
}
