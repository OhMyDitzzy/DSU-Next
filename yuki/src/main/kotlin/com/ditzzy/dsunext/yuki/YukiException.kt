package com.ditzzy.dsunext.yuki

import java.io.IOException

class YukiException : IOException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable?) : super(message, cause)
}
