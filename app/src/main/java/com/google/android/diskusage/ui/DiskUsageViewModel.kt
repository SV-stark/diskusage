package com.google.android.diskusage.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.google.android.diskusage.R
import com.google.android.diskusage.utils.AppHelper

class DiskUsageViewModel : ViewModel() {
    private val _toolbarActionButtonVisible = MutableStateFlow(false)
    val toolbarActionButtonVisible: StateFlow<Boolean> = _toolbarActionButtonVisible.asStateFlow()

    private val _showButton = MutableStateFlow(false)
    val showButton: StateFlow<Boolean> = _showButton.asStateFlow()

    private val _rescanButton = MutableStateFlow(false)
    val rescanButton: StateFlow<Boolean> = _rescanButton.asStateFlow()

    private val _deleteButton = MutableStateFlow(false)
    val deleteButton: StateFlow<Boolean> = _deleteButton.asStateFlow()

    private val _rendererButtonTitle = MutableStateFlow(AppHelper.appContext.getString(R.string.rederer))
    val rendererButtonTitle: StateFlow<String> = _rendererButtonTitle.asStateFlow()

    fun showToolbarActionButton() {
        _toolbarActionButtonVisible.value = true
    }

    fun hideToolBarActionButton() {
        _toolbarActionButtonVisible.value = false
    }

    fun enableShowButton() {
        _showButton.value = true
    }

    fun disableShowButton() {
        _showButton.value = false
    }

    fun enableRescanButton() {
        _rescanButton.value = true
    }

    fun disableRescanButton() {
        _rescanButton.value = false
    }

    fun enableDeleteButton() {
        _deleteButton.value = true
    }

    fun disableDeleteButton() {
        _deleteButton.value = false
    }

    fun setRendererButtonTitle(title: String) {
        _rendererButtonTitle.value = title
    }
}