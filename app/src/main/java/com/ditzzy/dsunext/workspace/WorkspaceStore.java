package com.ditzzy.dsunext.workspace;

import com.ditzzy.dsunext.yuki.Yuki;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public final class WorkspaceStore {

    public static final String BASE_DIR = "/data/local/dsu-next-workspace";
    public static final String PARTITION = "system";

    static final String PROP_FILE = "workspace.prop";
    static final String BUSY_FILE = ".busy";
    static final String STAGING_DIR = ".staging";
    static final String OUTPUT_DIR = "output";
    static final String CONFIG_DIR = "config";

    static final String K_PARTITION = "partition";
    static final String K_SOURCE = "source";
    static final String K_FS_TYPE = "fs_type";
    static final String K_MOUNT_POINT = "mount_point";
    static final String K_IMAGE_SIZE = "image_size";
    static final String K_IMPORTED_AT = "imported_at";
    static final String K_DIRS = "dirs";
    static final String K_FILES = "files";
    static final String K_SYMLINKS = "symlinks";
    static final String K_BYTES = "bytes";
    static final String K_REPACK_FILE = "repack_file";
    static final String K_REPACK_SIZE = "repack_size";
    static final String K_REPACK_AT = "repack_at";
    static final String K_ANDROID_RELEASE = "android_release";
    static final String K_ANDROID_SDK = "android_sdk";
    static final String K_BUILD_ID = "build_id";
    static final String K_FINGERPRINT = "fingerprint";

    private static final String[] INFO_KEYS = {
            K_PARTITION, K_SOURCE, K_FS_TYPE, K_MOUNT_POINT, K_IMAGE_SIZE, K_IMPORTED_AT,
            K_DIRS, K_FILES, K_SYMLINKS, K_BYTES, K_REPACK_FILE, K_REPACK_SIZE, K_REPACK_AT
    };
    private static final String[] CONFIG_KEYS = {"block_size", "uuid", "volume_name"};

    private WorkspaceStore() {
    }

    /** Empty when libyuki is usable in this process, the reason otherwise. */
    public static String nativeError() {
        if (Yuki.isLoaded()) {
            return "";
        }
        return "libyuki.so could not be loaded in the root process: " + Yuki.getLoadError();
    }

    static File dir(String name) throws IOException {
        if (!WorkspaceNames.isValid(name)) {
            throw new IOException("Invalid workspace name: " + name);
        }
        return new File(BASE_DIR, name);
    }

    static File existing(String name) throws IOException {
        File dir = dir(name);
        if (Files.isSymbolicLink(dir.toPath()) || !dir.isDirectory()) {
            throw new IOException("Workspace not found: " + name);
        }
        return dir;
    }

    static boolean isReady(File workspace) {
        return new File(workspace, PROP_FILE).isFile() && !new File(workspace, BUSY_FILE).exists();
    }

    static void markBusy(File workspace) throws IOException {
        Files.write(new File(workspace, BUSY_FILE).toPath(), new byte[0]);
    }

    static void clearBusy(File workspace) {
        //noinspection ResultOfMethodCallIgnored
        new File(workspace, BUSY_FILE).delete();
    }

    static Properties readProps(File workspace) {
        Properties props = new Properties();
        File file = new File(workspace, PROP_FILE);
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                props.load(in);
            } catch (IOException ignored) {
                // An unreadable file is treated like an empty one
            }
        }
        return props;
    }

    static void writeProps(File workspace, Properties props) throws IOException {
        try (OutputStream out = new FileOutputStream(new File(workspace, PROP_FILE))) {
            props.store(out, null);
        }
    }

    /** Removes a folder tree without ever following a symlink, a link is deleted as a link. */
    static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static boolean delete(String name) throws IOException {
        Path path = dir(name).toPath();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        deleteTree(path);
        return true;
    }

    public static String listJson() throws JSONException {
        JSONArray array = new JSONArray();
        File[] children = new File(BASE_DIR).listFiles();
        if (children != null) {
            Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File child : children) {
                if (!WorkspaceNames.isValid(child.getName())
                        || Files.isSymbolicLink(child.toPath())
                        || !child.isDirectory()) {
                    continue;
                }
                array.put(summary(child));
            }
        }
        return array.toString();
    }

    private static JSONObject summary(File workspace) throws JSONException {
        Properties props = readProps(workspace);
        JSONObject json = new JSONObject();
        json.put("name", workspace.getName());
        json.put("state", isReady(workspace) ? "ready" : "incomplete");
        json.put(K_FS_TYPE, props.getProperty(K_FS_TYPE, ""));
        json.put(K_IMAGE_SIZE, props.getProperty(K_IMAGE_SIZE, "0"));
        json.put(K_IMPORTED_AT, props.getProperty(K_IMPORTED_AT, "0"));
        json.put(K_SOURCE, props.getProperty(K_SOURCE, ""));
        json.put(K_ANDROID_RELEASE, props.getProperty(K_ANDROID_RELEASE, ""));
        return json;
    }

    public static String infoJson(String name) throws IOException, JSONException {
        File workspace = existing(name);
        Properties props = readProps(workspace);
        String partition = props.getProperty(K_PARTITION, PARTITION);

        JSONObject json = new JSONObject();
        json.put("name", name);
        json.put("path", workspace.getAbsolutePath());
        json.put("state", isReady(workspace) ? "ready" : "incomplete");
        for (String key : INFO_KEYS) {
            String value = props.getProperty(key);
            if (value != null) {
                json.put(key, value);
            }
        }

        Map<String, String> config = readKeyValues(
                new File(workspace, CONFIG_DIR + "/" + partition + "_info"));
        for (String key : CONFIG_KEYS) {
            String value = config.get(key);
            if (value != null && !value.isEmpty()) {
                json.put(key, value);
            }
        }

        // Read live: the user may have edited build.prop since the import
        for (Map.Entry<String, String> entry : readBuildInfo(workspace, partition).entrySet()) {
            json.put(entry.getKey(), entry.getValue());
        }
        return json.toString();
    }

    /** Android release, SDK, build id and fingerprint from the unpacked build.prop. */
    static Map<String, String> readBuildInfo(File workspace, String partition) {
        Map<String, String> result = new LinkedHashMap<>();
        File file = new File(workspace, partition + "/system/build.prop");
        if (!file.isFile()) {
            file = new File(workspace, partition + "/build.prop");
        }
        if (!file.isFile() || Files.isSymbolicLink(file.toPath())) {
            return result;
        }
        Map<String, String> prop = readKeyValues(file);
        putFirst(result, K_ANDROID_RELEASE, prop,
                "ro.system.build.version.release", "ro.build.version.release");
        putFirst(result, K_ANDROID_SDK, prop,
                "ro.system.build.version.sdk", "ro.build.version.sdk");
        putFirst(result, K_BUILD_ID, prop, "ro.build.display.id", "ro.system.build.id");
        putFirst(result, K_FINGERPRINT, prop,
                "ro.system.build.fingerprint", "ro.build.fingerprint");
        return result;
    }

    private static void putFirst(Map<String, String> into, String key, Map<String, String> from,
            String... candidates) {
        for (String candidate : candidates) {
            String value = from.get(candidate);
            if (value != null && !value.isEmpty()) {
                into.put(key, value);
                return;
            }
        }
    }

    private static Map<String, String> readKeyValues(File file) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!file.isFile()) {
            return values;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq <= 0 || line.startsWith("#")) {
                    continue;
                }
                values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (IOException ignored) {
            // Whatever was read before the failure is still useful
        }
        return values;
    }
}
