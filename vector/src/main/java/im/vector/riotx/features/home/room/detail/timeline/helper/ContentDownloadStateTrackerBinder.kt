/*
 * Copyright 2019 New Vector Ltd
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

package im.vector.riotx.features.home.room.detail.timeline.helper

import im.vector.matrix.android.internal.session.download.ContentDownloadStateTracker
import im.vector.riotx.R
import im.vector.riotx.core.di.ActiveSessionHolder
import im.vector.riotx.core.di.ScreenScope
import im.vector.riotx.core.error.ErrorFormatter
import im.vector.riotx.features.home.room.detail.timeline.MessageColorProvider
import im.vector.riotx.features.home.room.detail.timeline.item.MessageFileItem
import javax.inject.Inject

@ScreenScope
class ContentDownloadStateTrackerBinder @Inject constructor(private val activeSessionHolder: ActiveSessionHolder,
                                                            private val messageColorProvider: MessageColorProvider,
                                                            private val errorFormatter: ErrorFormatter) {

    private val updateListeners = mutableMapOf<String, ContentDownloadStateTracker.UpdateListener>()

    fun bind(mxcUrl: String,
             holder: MessageFileItem.Holder) {
        activeSessionHolder.getSafeActiveSession()?.also { session ->
            val downloadStateTracker = session.contentDownloadProgressTracker()
            val updateListener = ContentDownloadUpdater(holder, messageColorProvider, errorFormatter)
            updateListeners[mxcUrl] = updateListener
            downloadStateTracker.track(mxcUrl, updateListener)
        }
    }

    fun unbind(mxcUrl: String) {
        activeSessionHolder.getSafeActiveSession()?.also { session ->
            val downloadStateTracker = session.contentDownloadProgressTracker()
            updateListeners[mxcUrl]?.also {
                downloadStateTracker.unTrack(mxcUrl, it)
            }
        }
    }

    fun clear() {
        activeSessionHolder.getSafeActiveSession()?.also {
            it.contentUploadProgressTracker().clear()
        }
    }
}

private class ContentDownloadUpdater(private val holder: MessageFileItem.Holder,
                                     private val messageColorProvider: MessageColorProvider,
                                     private val errorFormatter: ErrorFormatter) : ContentDownloadStateTracker.UpdateListener {

    override fun onDownloadStateUpdate(state: ContentDownloadStateTracker.State) {
        when (state) {
            ContentDownloadStateTracker.State.Idle           -> handleIdle()
            is ContentDownloadStateTracker.State.Downloading -> handleProgress(state)
            ContentDownloadStateTracker.State.Decrypting     -> handleDecrypting()
            ContentDownloadStateTracker.State.Success        -> handleSuccess()
            is ContentDownloadStateTracker.State.Failure     -> handleFailure()
        }
    }

    // avoid blink effect when setting icon
    private var hasDLResource = false

    private fun handleIdle() {
        holder.fileDownloadProgress.progress = 0
        holder.fileDownloadProgress.isIndeterminate = false
    }

    private fun handleDecrypting() {
        holder.fileDownloadProgress.isIndeterminate = true
    }

    private fun handleProgress(state: ContentDownloadStateTracker.State.Downloading) {
        doHandleProgress(state.current, state.total)
    }

    private fun doHandleProgress(current: Long, total: Long) {
        val percent = 100L * (current.toFloat() / total.toFloat())
        holder.fileDownloadProgress.isIndeterminate = false
        holder.fileDownloadProgress.progress = percent.toInt()
        if (!hasDLResource) {
            holder.fileImageView.setImageResource(R.drawable.ic_download)
            hasDLResource = true
        }
    }

    private fun handleFailure() {
        holder.fileDownloadProgress.isIndeterminate = false
        holder.fileDownloadProgress.progress = 0
        holder.fileImageView.setImageResource(R.drawable.ic_close_round)
    }

    private fun handleSuccess() {
        holder.fileDownloadProgress.isIndeterminate = false
        holder.fileDownloadProgress.progress = 100
        holder.fileImageView.setImageResource(R.drawable.ic_paperclip)
    }
}
