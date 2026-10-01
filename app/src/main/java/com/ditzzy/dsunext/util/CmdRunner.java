package com.ditzzy.dsunext.util;

import com.topjohnwu.superuser.CallbackList;
import com.topjohnwu.superuser.Shell;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs shell commands using the root shell when available, or a plain shell otherwise.
 */
public final class CmdRunner {

    private static volatile Process process;

    private CmdRunner() {
    }

    public static String run(String cmd) {
        if (Shell.getShell().isRoot()) {
            List<String> out = Shell.cmd(cmd).exec().getOut();
            return String.join("\n", out);
        }
        StringBuilder output = new StringBuilder();
        runCommand(cmd, line -> output.append(line).append('\n'));
        return output.toString();
    }

    /**
     * Streams every line of output to {@code onReceive}. On the root shell this returns
     * immediately, otherwise it blocks until the command finishes or {@link #destroy()} is called.
     */
    public static void runReadEachLine(String cmd, Consumer<String> onReceive) {
        if (Shell.getShell().isRoot()) {
            // Lines are delivered on the main thread, the default executor of CallbackList
            CallbackList<String> callbackList = new CallbackList<String>() {
                @Override
                public void onAddElement(String line) {
                    onReceive.accept(line);
                }
            };
            Shell.cmd(cmd).to(callbackList).submit();
            return;
        }
        runCommand(cmd, onReceive);
    }

    private static void runCommand(String cmd, Consumer<String> onReceive) {
        try {
            Process started = new ProcessBuilder("/bin/sh", "-c", cmd).start();
            process = started;
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(started.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isEmpty()) {
                        onReceive.accept(line);
                    }
                }
            }
        } catch (IOException ignored) {
            // Thrown when the process is destroyed while we are still reading its output
        }
    }

    public static void destroy() {
        if (Shell.getShell().isRoot()) {
            // The only way to stop a running command in the shared shell is to recycle it
            try {
                Shell.getShell().close();
            } catch (IOException ignored) {
                // Nothing to recover from, a new shell is requested below anyway
            }
            // Requested asynchronously, since this may be called from the main thread
            Shell.getShell(shell -> { });
            return;
        }
        Process current = process;
        if (current != null) {
            current.destroy();
            process = null;
        }
    }
}
