package com.localyze.utils

import android.util.Log
import com.localyze.BuildConfig

/**
 * Thin logging wrapper. d() and v() are no-ops in release builds so debug
 * tracing — which can include user prompts, model paths, and inference
 * params — never reaches logcat in production.
 *
 * w() and e() still pass through (release crash diagnostics). Use Log.e
 * directly only when you also want the raw stack on debug + release.
 */
internal object AppLog {
    inline fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg)
    }

    inline fun v(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.v(tag, msg)
    }

    inline fun w(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
    }

    inline fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
    }
}
