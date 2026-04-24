package com.localyze

import com.localyze.data.repository.shouldDeleteStaleTempFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStartPolicyTest {

    @Test
    fun keepsTempFileOnlyWhenResumeIsRequestedAndMetadataIsValid() {
        assertFalse(
            shouldDeleteStaleTempFile(
                resumeRequested = true,
                canResumeExistingDownload = true,
                tempFileExists = true
            )
        )
    }

    @Test
    fun deletesTempFileWhenResumeMetadataIsMissingOrInvalid() {
        assertTrue(
            shouldDeleteStaleTempFile(
                resumeRequested = true,
                canResumeExistingDownload = false,
                tempFileExists = true
            )
        )
    }

    @Test
    fun deletesTempFileWhenCallerRequestsFreshDownload() {
        assertTrue(
            shouldDeleteStaleTempFile(
                resumeRequested = false,
                canResumeExistingDownload = false,
                tempFileExists = true
            )
        )
    }

    @Test
    fun doesNothingWhenNoTempFileExists() {
        assertFalse(
            shouldDeleteStaleTempFile(
                resumeRequested = true,
                canResumeExistingDownload = false,
                tempFileExists = false
            )
        )
    }
}
