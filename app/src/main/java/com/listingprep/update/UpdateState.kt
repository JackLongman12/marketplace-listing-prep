package com.listingprep.update

import kotlinx.coroutines.flow.MutableStateFlow

/** Shared between the activity, the install receiver and the view model. */
object UpdateState {
    /** True while Listing Prep is on screen; installs then wait until you leave the app. */
    @Volatile var appVisible = false

    val error = MutableStateFlow<String?>(null)

    fun failed(message: String) {
        error.value = "Update failed: $message"
    }
}
