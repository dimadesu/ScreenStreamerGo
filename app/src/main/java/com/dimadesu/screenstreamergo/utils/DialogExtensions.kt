/*
 * Copyright (C) 2026 Dmytro Antonov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dimadesu.screenstreamergo.utils

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.dimadesu.screenstreamergo.R

/**
 * Displays a simple alert dialog with an OK button.
 */
fun Context.showAlertDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit = {}
) {
    AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(android.R.string.ok) { dialog, _ ->
            dialog.dismiss()
            onConfirm()
        }
        .show()
}

/**
 * Displays a permission explanation alert dialog with an OK button.
 */
fun Context.showPermissionAlertDialog(
    onConfirm: () -> Unit = {}
) {
    AlertDialog.Builder(this)
        .setTitle(R.string.permission)
        .setMessage(R.string.permission_not_granted)
        .setPositiveButton(android.R.string.ok) { dialog, _ ->
            dialog.dismiss()
            onConfirm()
        }
        .show()
}
