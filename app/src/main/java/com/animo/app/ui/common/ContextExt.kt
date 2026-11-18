package com.animo.app.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** The activity behind a (possibly wrapped) context, for window-level effects. */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
