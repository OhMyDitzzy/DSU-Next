package com.ditzzy.dsunext.workspace;

public interface JobReporter {
    void log(String line);
    void progress(int percent);
}
