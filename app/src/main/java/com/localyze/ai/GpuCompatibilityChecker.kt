package com.localyze.ai

import android.opengl.EGL14
import android.os.Build
import com.localyze.utils.AppLog
import java.io.File

/**
 * Pre-flight GPU compatibility check run before attempting Backend.GPU() initialization.
 *
 * SIGABRT from the native GPU driver (liblitertlm_jni.so) cannot be caught in Kotlin —
 * it kills the entire process. These checks filter out clearly-incompatible devices
 * *before* we attempt GPU init, avoiding the crash on those devices entirely.
 *
 * For devices that pass all checks but still crash during GPU init, the
 * CrashRecoveryStore GPU init sentinel detects the crash on the next launch
 * and forces CPU-only — so no crash loop is possible in either case.
 *
 * Why these checks:
 * - ARM check:     emulators use x86/x86_64 and have no OpenCL ICD at all.
 * - OpenCL check:  LiteRT-LM GPU backend uses OpenCL compute shaders; if the
 *                  vendor library isn't present the driver will SIGABRT trying
 *                  to open it. Google Tensor (Pixel 6/7/8) ships only Vulkan/GL,
 *                  no OpenCL — confirmed no-go for LiteRT-LM GPU.
 * - EGL check:     If the GPU driver can't even initialise an EGL display it is
 *                  non-functional at a basic level; GPU inference will definitely fail.
 */
object GpuCompatibilityChecker {

    private const val TAG = "GpuCompatibility"

    // Standard vendor OpenCL library paths across major Android SoC families:
    //   Qualcomm Snapdragon  → /vendor/lib64/libOpenCL.so
    //   ARM Mali (Exynos)    → /vendor/lib64/libOpenCL.so (same path, different binary)
    //   MediaTek             → /vendor/lib64/libOpenCL.so
    //   Google Tensor        → absent (Tensor uses Vulkan, not OpenCL)
    private val OPENCL_PATHS = listOf(
        "/vendor/lib64/libOpenCL.so",
        "/system/vendor/lib64/libOpenCL.so",
        "/vendor/lib/libOpenCL.so",
        "/system/lib64/libOpenCL.so",
        "/system/lib/libOpenCL.so",
    )

    /**
     * Returns true if the GPU backend is likely safe to attempt initialization with.
     *
     * Even when this returns true, a SIGABRT is still possible on some devices with
     * buggy GPU drivers (rare, undetectable without triggering the driver). Those cases
     * are caught by the CrashRecoveryStore sentinel on the next launch.
     */
    fun isGpuLikelySafe(): Boolean {
        if (!isRealArmDevice()) {
            AppLog.d(TAG, "GPU skip: not an ARM device (emulator or x86 target)")
            return false
        }
        if (!isOpenClPresent()) {
            AppLog.w(TAG, "GPU skip: OpenCL library absent from all vendor paths (${Build.MANUFACTURER} ${Build.MODEL})")
            return false
        }
        if (!isEglFunctional()) {
            AppLog.w(TAG, "GPU skip: EGL display init failed — GPU driver non-functional")
            return false
        }
        AppLog.d(TAG, "GPU pre-flight OK: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.HARDWARE})")
        return true
    }

    // Real ARM devices have at least one ABI starting with "arm".
    // Emulators run x86/x86_64 even when emulating ARM.
    private fun isRealArmDevice(): Boolean =
        Build.SUPPORTED_ABIS.any { it.startsWith("arm") }

    private fun isOpenClPresent(): Boolean =
        OPENCL_PATHS.any { File(it).exists() }

    // Lightweight EGL probe — initialises the display and immediately terminates.
    // Does not create a context or allocate GPU memory; safe to call from any thread.
    private fun isEglFunctional(): Boolean {
        return try {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return false
            val major = IntArray(1)
            val minor = IntArray(1)
            val ok = EGL14.eglInitialize(display, major, 0, minor, 0)
            if (ok) {
                EGL14.eglTerminate(display)
                AppLog.d(TAG, "EGL v${major[0]}.${minor[0]} OK")
            }
            ok
        } catch (e: Exception) {
            AppLog.w(TAG, "EGL probe threw: ${e.message}")
            false
        }
    }
}
