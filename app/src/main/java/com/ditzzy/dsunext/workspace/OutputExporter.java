package com.ditzzy.dsunext.workspace;

import android.os.ParcelFileDescriptor;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.regex.Pattern;

public final class OutputExporter {

    private static final Pattern OUTPUT_FILE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}\\.img$");
    private static final long CHUNK = 4L * 1024L * 1024L;

    private OutputExporter() {
    }

    /** Takes ownership of {@code target}. */
    public static String run(String name, String fileName, ParcelFileDescriptor target,
            JobReporter reporter) throws IOException, JSONException {
        try (ParcelFileDescriptor.AutoCloseOutputStream out =
                     new ParcelFileDescriptor.AutoCloseOutputStream(target)) {
            File workspace = WorkspaceStore.existing(name);
            if (!OUTPUT_FILE.matcher(fileName).matches()) {
                throw new IOException("Invalid file name: " + fileName);
            }
            File file = new File(new File(workspace, WorkspaceStore.OUTPUT_DIR), fileName);
            if (!file.isFile() || Files.isSymbolicLink(file.toPath())) {
                throw new IOException("There is nothing to save, repack the workspace first");
            }

            long total = file.length();
            reporter.log("- Saving " + fileName);
            try (FileInputStream in = new FileInputStream(file)) {
                FileChannel from = in.getChannel();
                FileChannel to = out.getChannel();
                long position = 0L;
                int lastPercent = -1;
                while (position < total) {
                    long moved = from.transferTo(position, Math.min(CHUNK, total - position), to);
                    if (moved <= 0L) {
                        throw new IOException("The copy stopped after " + position + " bytes");
                    }
                    position += moved;
                    int percent = (int) Math.min(99L, position * 100L / total);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        reporter.progress(percent);
                    }
                }
            }
            out.flush();
            reporter.progress(100);
            return new JSONObject().put("name", name).put("file", fileName).toString();
        }
    }
}
