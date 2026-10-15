package com.jadbarakat.blooddonor.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** A user-facing message, resolved to text in the UI so ViewModels stay Context-free. */
data class UiMessage(@param:StringRes val resId: Int, val args: List<String> = emptyList())

@Composable
fun UiMessage.resolve(): String = stringResource(resId, *args.toTypedArray())
