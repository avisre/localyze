package com.localyze.data.repository

internal fun shouldDeleteStaleTempFile(
    resumeRequested: Boolean,
    canResumeExistingDownload: Boolean,
    tempFileExists: Boolean
): Boolean {
    return tempFileExists && !(resumeRequested && canResumeExistingDownload)
}
