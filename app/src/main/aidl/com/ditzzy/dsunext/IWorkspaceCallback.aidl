package com.ditzzy.dsunext;

oneway interface IWorkspaceCallback {
    void onLog(String line);
    void onProgress(int percent);
    void onFinished(String resultJson);
    void onError(String message);
}
