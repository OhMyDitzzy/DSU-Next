package com.ditzzy.dsunext.workspace

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class WorkspaceJobs {
    private constructor() {

    }

    companion object {
        private val WORKER: ExecutorService = Executors.newSingleThreadExecutor()

        @JvmStatic
        fun executor(): ExecutorService {
            return WORKER
        }
    }
}
