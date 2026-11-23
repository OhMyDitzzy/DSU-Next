package com.ditzzy.dsunext.workspace

interface JobReporter {
    fun log(line: String)

    fun progress(percent: Int)
}
