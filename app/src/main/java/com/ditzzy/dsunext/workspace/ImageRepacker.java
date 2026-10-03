package com.ditzzy.dsunext.workspace;

import com.ditzzy.dsunext.yuki.BuildResult;
import com.ditzzy.dsunext.yuki.Yuki;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

public final class ImageRepacker {

    private ImageRepacker() {
    }

    /**
     * @param size null for the original size, "auto", or bytes with an optional K, M or G.
     * @return a JSON object that describes the image that was written.
     */
    public static String run(String name, String size, String outputName, JobReporter reporter)
            throws IOException, JSONException {
        String nativeError = WorkspaceStore.nativeError();
        if (!nativeError.isEmpty()) {
            throw new IOException(nativeError);
        }
        File workspace = WorkspaceStore.existing(name);
        if (!WorkspaceStore.isReady(workspace)) {
            throw new IOException("This workspace is incomplete, delete it and import the image again");
        }
        if (!WorkspaceNames.isValid(outputName)) {
            throw new IOException("Invalid output name: " + outputName);
        }
        if (size != null && !WorkspaceNames.isValidSize(size)) {
            throw new IOException("Invalid size: " + size);
        }

        Properties props = WorkspaceStore.readProps(workspace);
        String partition = props.getProperty(WorkspaceStore.K_PARTITION, WorkspaceStore.PARTITION);

        File outputDir = new File(workspace, WorkspaceStore.OUTPUT_DIR);
        if (!outputDir.isDirectory() && !outputDir.mkdirs()) {
            throw new IOException("Cannot create " + outputDir);
        }
        String fileName = outputName + ".img";
        File output = new File(outputDir, fileName);

        reporter.progress(-1);
        BuildResult result;
        try {
            result = Yuki.buildImage(workspace, partition, output, size, true, reporter::log);
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            if (size == null) {
                reporter.log("! If the files no longer fit, repack again with the size set to Auto "
                        + "or to something larger");
            }
            throw e;
        }

        for (String warning : result.getWarnings()) {
            reporter.log("! " + warning);
        }
        if (!result.getNewEntries().isEmpty()) {
            reporter.log("- " + result.getNewEntries().size()
                    + " new file(s) had no metadata and got default permissions and labels");
        }

        long length = output.length();
        props.setProperty(WorkspaceStore.K_REPACK_FILE, fileName);
        props.setProperty(WorkspaceStore.K_REPACK_SIZE, Long.toString(length));
        props.setProperty(WorkspaceStore.K_REPACK_AT, Long.toString(System.currentTimeMillis()));
        WorkspaceStore.writeProps(workspace, props);

        reporter.progress(100);
        JSONArray warnings = new JSONArray();
        for (String warning : result.getWarnings()) {
            warnings.put(warning);
        }
        return new JSONObject()
                .put("name", name)
                .put("file", fileName)
                .put("size", length)
                .put("new_entries", result.getNewEntries().size())
                .put("removed", result.getRemovedEntries())
                .put("warnings", warnings)
                .toString();
    }
}
