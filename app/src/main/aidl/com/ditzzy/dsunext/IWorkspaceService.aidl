package com.ditzzy.dsunext;

import android.os.ParcelFileDescriptor;
import com.ditzzy.dsunext.IWorkspaceCallback;

interface IWorkspaceService {
    /** Empty when the service can do its work, the reason otherwise (not root, libyuki missing). */
    String getNativeError();

    /** JSON array with one summary object per workspace. */
    String listWorkspaces();

    /** JSON object with the details of one workspace, empty when it does not exist. */
    String getWorkspaceInfo(String name);

    boolean deleteWorkspace(String name);

    /** Unpacks an image, or an archive that holds one, into a new workspace. Returns at once. */
    void importWorkspace(in ParcelFileDescriptor source, String sourceName, String workspaceName,
            IWorkspaceCallback callback);

    /** [size] is null for the original size, "auto", or bytes with an optional K, M or G. */
    void repackWorkspace(String workspaceName, String size, String outputName,
            IWorkspaceCallback callback);

    /** Copies a repacked image from the workspace into [target]. */
    void exportOutput(String workspaceName, String fileName, in ParcelFileDescriptor target,
            IWorkspaceCallback callback);
}
