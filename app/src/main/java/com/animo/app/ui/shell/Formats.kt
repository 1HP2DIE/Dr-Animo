package com.animo.app.ui.shell

import java.text.NumberFormat
import java.util.Locale

/** 1942 -> "1,942". Shared by the drawer header and the home hero subtitle. */
internal fun formatCount(count: Int): String =
    NumberFormat.getIntegerInstance(Locale.US).format(count)
