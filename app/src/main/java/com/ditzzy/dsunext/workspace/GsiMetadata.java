package com.ditzzy.dsunext.workspace;

import com.ditzzy.dsunext.filemanager.api.ChangeKind;
import com.ditzzy.dsunext.filemanager.api.FileChange;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/* The image's owners and modes belong in Yuki's sidecars; working files stay owned by the app. */
public final class GsiMetadata {
    /* Stock images list symlinks in fs_config like regular files (0644), not as 0777. */
    public static final int SYMLINK_MODE = 0644;
    private final Path config;
    private final Path marker;
    private final Path root;
    private final String partition;
    private final String mount;
    private final TreeMap<String, String> modes = new TreeMap<>();
    private final TreeMap<String, String> labels = new TreeMap<>();
    private final TreeMap<String, String> links = new TreeMap<>();

    public GsiMetadata(File workspace, String partition) throws IOException {
        this.partition = partition;
        root = new File(workspace, partition).toPath();
        config = new File(workspace, "config").toPath();
        marker = new File(workspace, ".metadata-pending").toPath();
        Properties info = new Properties();
        try (java.io.InputStream input = Files.newInputStream(file("info"))) { info.load(input); }
        mount = info.getProperty("mount_point", "/" + partition);
        if (!mount.startsWith("/") || mount.contains("..")) throw new IOException("Invalid image mount point");
        load(file("fs_config"), modes, false, true);
        load(file("file_contexts"), labels, true, false);
        load(file("symlinks"), links, false, true);
    }

    public static void checkHealthy(File workspace) throws IOException {
        if (Files.exists(new File(workspace, ".metadata-pending").toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("An interrupted edit needs recovery. Import this workspace again before editing or repacking.");
        }
    }

    public void begin() throws IOException {
        Files.write(marker, new byte[]{1}, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try (FileChannel channel = FileChannel.open(marker, StandardOpenOption.WRITE)) { channel.force(true); }
    }

    public void abort() throws IOException { Files.deleteIfExists(marker); }

    public void save() throws IOException {
        write(file("fs_config"), modes, false, true);
        write(file("file_contexts"), labels, true, false);
        write(file("symlinks"), links, false, true);
        Files.delete(marker);
    }

    public int mode(String path, boolean directory, boolean symlink) {
        String value = modes.get(device(path));
        if (value != null) {
            String[] fields = value.split("\\s+");
            if (fields.length >= 3) return Integer.parseInt(fields[2], 8) & 07777;
        }
        return symlink ? SYMLINK_MODE : directory ? 0755 : executable(path) ? 0755 : 0644;
    }

    public void setMode(String path, int mode) throws IOException {
        String key = device(path);
        ensure(key, path);
        String[] fields = modes.get(key).split("\\s+");
        fields[2] = String.format(java.util.Locale.ROOT, "%04o", mode);
        modes.put(key, String.join(" ", fields));
    }

    public void apply(FileChange change) throws IOException {
        String path = change.getPath();
        validatePath(path);
        String target = device(path);
        String source = change.getSource() == null ? null : device(change.getSource());
        if (change.getKind() == ChangeKind.MOVED) {
            if (modes.containsKey(source)) {
                relocate(modes, source, target);
                relocate(labels, source, target);
                relocate(links, source, target);
            }
            ensure(target, path);
            return;
        }
        if (change.getKind() == ChangeKind.DELETED) {
            remove(modes, target); remove(labels, target); remove(links, target);
            return;
        }
        if (source != null) {
            copy(modes, source, target); copy(labels, source, target); copy(links, source, target);
        }
        ensure(target, path);
        Path actual = root.resolve(path);
        if (Files.isSymbolicLink(actual)) links.put(target, Files.readSymbolicLink(actual).toString());
        else links.remove(target);
    }

    private void ensure(String key, String path) throws IOException {
        validatePath(path);
        Path actual = root.resolve(path);
        boolean directory = Files.isDirectory(actual, LinkOption.NOFOLLOW_LINKS);
        boolean link = Files.isSymbolicLink(actual);
        boolean newEntry = !modes.containsKey(key);
        if (newEntry) {
            int mode = link ? SYMLINK_MODE : directory ? 0755 : executable(path) ? 0755 : 0644;
            modes.put(key, "0 " + (!directory && !link && executable(path) ? "2000" : "0") + " "
                    + String.format(java.util.Locale.ROOT, "%04o", mode));
        }
        if (newEntry && !labels.containsKey(key)) {
            String parent = key;
            while (parent.lastIndexOf('/') > 0) {
                parent = parent.substring(0, parent.lastIndexOf('/'));
                if (labels.containsKey(parent)) { labels.put(key, labels.get(parent)); return; }
            }
            if (labels.containsKey("/")) labels.put(key, labels.get("/"));
        }
    }

    private String device(String path) {
        if (path.isEmpty()) return mount;
        return (mount.equals("/") ? "" : mount) + "/" + path;
    }

    public static void validatePath(String path) throws IOException {
        if (path.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new IOException("GSI file names cannot contain whitespace or control characters");
        }
    }

    private static boolean executable(String path) {
        int slash = path.lastIndexOf('/');
        String parent = slash < 0 ? "" : path.substring(0, slash);
        String name = parent.substring(parent.lastIndexOf('/') + 1);
        return name.equals("bin") || name.equals("xbin");
    }

    private static void copy(TreeMap<String, String> map, String source, String target) {
        String value = map.get(source);
        if (value == null) map.remove(target); else map.put(target, value);
    }

    private static void remove(TreeMap<String, String> map, String root) {
        map.keySet().removeIf(key -> key.equals(root) || key.startsWith(root + "/"));
    }

    private static void relocate(TreeMap<String, String> map, String source, String target) {
        TreeMap<String, String> moved = new TreeMap<>();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (entry.getKey().equals(source) || entry.getKey().startsWith(source + "/")) {
                moved.put(target + entry.getKey().substring(source.length()), entry.getValue());
            }
        }
        remove(map, target);
        remove(map, source);
        map.putAll(moved);
    }

    private Path file(String suffix) { return config.resolve(partition + "_" + suffix); }

    private static void load(Path file, TreeMap<String, String> map, boolean regex, boolean relative) throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) continue;
            int separator = line.indexOf(' ');
            if (separator <= 0) throw new IOException("Unreadable image metadata: " + file.getFileName());
            String key = line.substring(0, separator);
            if (regex) key = unescape(key);
            if (relative && !key.equals("/")) key = "/" + key;
            String value = line.substring(separator + 1);
            if (!file.toString().endsWith("_symlinks")) value = value.trim();
            if (file.toString().endsWith("_fs_config")) {
                String[] fields = value.split("\\s+");
                try {
                    if (fields.length < 3) throw new NumberFormatException();
                    Long.parseLong(fields[0]); Long.parseLong(fields[1]); Integer.parseInt(fields[2], 8);
                } catch (NumberFormatException error) { throw new IOException("Unreadable file permissions", error); }
            }
            map.put(key, value);
        }
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) c = value.charAt(++i);
            out.append(c);
        }
        return out.toString();
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (".^$*+?()[]{}|\\".indexOf(c) >= 0) out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    private static void write(Path file, TreeMap<String, String> values, boolean regex, boolean relative) throws IOException {
        ArrayList<String> lines = new ArrayList<>(values.size());
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            if (relative && !key.equals("/")) key = key.substring(1);
            if (regex) key = escape(key);
            lines.add(key + " " + entry.getValue());
        }
        Path temp = Files.createTempFile(file.getParent(), ".metadata-", ".tmp");
        try {
            Files.write(temp, lines, StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
