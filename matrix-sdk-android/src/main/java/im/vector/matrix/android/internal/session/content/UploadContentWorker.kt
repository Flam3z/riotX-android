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

package im.vector.matrix.android.internal.session.content

import android.content.Context
import android.graphics.BitmapFactory
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.squareup.moshi.JsonClass
import id.zelory.compressor.Compressor
import id.zelory.compressor.constraint.default
import im.vector.matrix.android.api.session.content.ContentAttachmentData
import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.api.session.events.model.toContent
import im.vector.matrix.android.api.session.events.model.toModel
import im.vector.matrix.android.api.session.room.model.message.MessageAudioContent
import im.vector.matrix.android.api.session.room.model.message.MessageContent
import im.vector.matrix.android.api.session.room.model.message.MessageFileContent
import im.vector.matrix.android.api.session.room.model.message.MessageImageContent
import im.vector.matrix.android.api.session.room.model.message.MessageVideoContent
import im.vector.matrix.android.internal.crypto.attachments.MXEncryptedAttachments
import im.vector.matrix.android.internal.crypto.model.rest.EncryptedFileInfo
import im.vector.matrix.android.internal.network.ProgressRequestBody
import im.vector.matrix.android.internal.session.room.send.MultipleEventSendingDispatcherWorker
import im.vector.matrix.android.internal.worker.SessionWorkerParams
import im.vector.matrix.android.internal.worker.WorkerParamsFactory
import im.vector.matrix.android.internal.worker.getSessionComponent
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject

private data class NewImageAttributes(
        val newWidth: Int?,
        val newHeight: Int?,
        val newFileSize: Int
)

/**
 * Possible previous worker: None
 * Possible next worker    : Always [MultipleEventSendingDispatcherWorker]
 */
internal class UploadContentWorker(val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @JsonClass(generateAdapter = true)
    internal data class Params(
            override val sessionId: String,
            val events: List<Event>,
            val attachment: ContentAttachmentData,
            val isEncrypted: Boolean,
            val compressBeforeSending: Boolean,
            override val lastFailureMessage: String? = null
    ) : SessionWorkerParams

    @Inject lateinit var fileUploader: FileUploader
    @Inject lateinit var contentUploadStateTracker: DefaultContentUploadStateTracker

    override suspend fun doWork(): Result {
        val params = WorkerParamsFactory.fromData<Params>(inputData)
                ?: return Result.success()
                        .also { Timber.e("Unable to parse work parameters") }

        Timber.v("Starting upload media work with params $params")

        if (params.lastFailureMessage != null) {
            // Transmit the error
            return Result.success(inputData)
                    .also { Timber.e("Work cancelled due to input error from parent") }
        }

        // Just defensive code to ensure that we never have an uncaught exception that could break the queue
        return try {
            internalDoWork(params)
        } catch (failure: Throwable) {
            Timber.e(failure)
            handleFailure(params, failure)
        }
    }

    private suspend fun internalDoWork(params: Params): Result {
        val sessionComponent = getSessionComponent(params.sessionId) ?: return Result.success()
        sessionComponent.inject(this)

        val attachment = params.attachment

        var newImageAttributes: NewImageAttributes? = null

        try {
            val inputStream = context.contentResolver.openInputStream(attachment.queryUri)
                    ?: return Result.success(
                            WorkerParamsFactory.toData(
                                    params.copy(
                                            lastFailureMessage = "Cannot openInputStream for file: " + attachment.queryUri.toString()
                                    )
                            )
                    )

            inputStream.use {
                var uploadedThumbnailUrl: String? = null
                var uploadedThumbnailEncryptedFileInfo: EncryptedFileInfo? = null

                ThumbnailExtractor.extractThumbnail(context, params.attachment)?.let { thumbnailData ->
                    val thumbnailProgressListener = object : ProgressRequestBody.Listener {
                        override fun onProgress(current: Long, total: Long) {
                            notifyTracker(params) { contentUploadStateTracker.setProgressThumbnail(it, current, total) }
                        }
                    }

                    try {
                        val contentUploadResponse = if (params.isEncrypted) {
                            Timber.v("Encrypt thumbnail")
                            notifyTracker(params) { contentUploadStateTracker.setEncryptingThumbnail(it) }
                            val encryptionResult = MXEncryptedAttachments.encryptAttachment(ByteArrayInputStream(thumbnailData.bytes), thumbnailData.mimeType)
                            uploadedThumbnailEncryptedFileInfo = encryptionResult.encryptedFileInfo
                            fileUploader.uploadByteArray(encryptionResult.encryptedByteArray,
                                    "thumb_${attachment.name}",
                                    "application/octet-stream",
                                    thumbnailProgressListener)
                        } else {
                            fileUploader.uploadByteArray(thumbnailData.bytes,
                                    "thumb_${attachment.name}",
                                    thumbnailData.mimeType,
                                    thumbnailProgressListener)
                        }

                        uploadedThumbnailUrl = contentUploadResponse.contentUri
                    } catch (t: Throwable) {
                        Timber.e(t, "Thumbnail update failed")
                    }
                }

                val progressListener = object : ProgressRequestBody.Listener {
                    override fun onProgress(current: Long, total: Long) {
                        notifyTracker(params) {
                            if (isStopped) {
                                contentUploadStateTracker.setFailure(it, Throwable("Cancelled"))
                            } else {
                                contentUploadStateTracker.setProgress(it, current, total)
                            }
                        }
                    }
                }

                var uploadedFileEncryptedFileInfo: EncryptedFileInfo? = null

                return try {
                    // Compressor library works with File instead of Uri for now. Since Scoped Storage doesn't allow us to access files directly, we should
                    // copy it to a cache folder by using InputStream and OutputStream.
                    // https://github.com/zetbaitsu/Compressor/pull/150
                    // As soon as the above PR is merged, we can use attachment.queryUri instead of creating a cacheFile.
                    var cacheFile = File.createTempFile(attachment.name ?: UUID.randomUUID().toString(), ".jpg", context.cacheDir)
                    cacheFile.parentFile?.mkdirs()
                    if (cacheFile.exists()) {
                        cacheFile.delete()
                    }
                    cacheFile.createNewFile()
                    cacheFile.deleteOnExit()

                    val outputStream = FileOutputStream(cacheFile)
                    outputStream.use {
                        inputStream.copyTo(outputStream)
                    }

                    if (attachment.type == ContentAttachmentData.Type.IMAGE && params.compressBeforeSending) {
                        cacheFile = Compressor.compress(context, cacheFile) {
                            default(
                                    width = MAX_IMAGE_SIZE,
                                    height = MAX_IMAGE_SIZE
                            )
                        }.also { compressedFile ->
                            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(compressedFile.absolutePath, options)
                            val fileSize = compressedFile.length().toInt()
                            newImageAttributes = NewImageAttributes(
                                    options.outWidth,
                                    options.outHeight,
                                    fileSize
                            )
                        }
                    }

                    val contentUploadResponse = if (params.isEncrypted) {
                        Timber.v("Encrypt file")
                        notifyTracker(params) { contentUploadStateTracker.setEncrypting(it) }

                        val encryptionResult = MXEncryptedAttachments.encryptAttachment(FileInputStream(cacheFile), attachment.getSafeMimeType())
                        uploadedFileEncryptedFileInfo = encryptionResult.encryptedFileInfo

                        fileUploader
                                .uploadByteArray(encryptionResult.encryptedByteArray, attachment.name, "application/octet-stream", progressListener)
                    } else {
                        fileUploader
                                .uploadFile(cacheFile, attachment.name, attachment.getSafeMimeType(), progressListener)
                    }

                    handleSuccess(params,
                            contentUploadResponse.contentUri,
                            uploadedFileEncryptedFileInfo,
                            uploadedThumbnailUrl,
                            uploadedThumbnailEncryptedFileInfo,
                            newImageAttributes)
                } catch (t: Throwable) {
                    Timber.e(t)
                    handleFailure(params, t)
                }
            }
        } catch (e: Exception) {
            Timber.e(e)
            notifyTracker(params) { contentUploadStateTracker.setFailure(it, e) }
            return Result.success(
                    WorkerParamsFactory.toData(
                            params.copy(
                                    lastFailureMessage = e.localizedMessage
                            )
                    )
            )
        }
    }

    private fun handleFailure(params: Params, failure: Throwable): Result {
        notifyTracker(params) { contentUploadStateTracker.setFailure(it, failure) }

        return Result.success(
                WorkerParamsFactory.toData(
                        params.copy(
                                lastFailureMessage = failure.localizedMessage
                        )
                )
        )
    }

    private fun handleSuccess(params: Params,
                              attachmentUrl: String,
                              encryptedFileInfo: EncryptedFileInfo?,
                              thumbnailUrl: String?,
                              thumbnailEncryptedFileInfo: EncryptedFileInfo?,
                              newImageAttributes: NewImageAttributes?): Result {
        Timber.v("handleSuccess $attachmentUrl, work is stopped $isStopped")
        notifyTracker(params) { contentUploadStateTracker.setSuccess(it) }

        val updatedEvents = params.events
                .map {
                    updateEvent(it, attachmentUrl, encryptedFileInfo, thumbnailUrl, thumbnailEncryptedFileInfo, newImageAttributes)
                }

        val sendParams = MultipleEventSendingDispatcherWorker.Params(params.sessionId, updatedEvents, params.isEncrypted)
        return Result.success(WorkerParamsFactory.toData(sendParams))
    }

    private fun updateEvent(event: Event,
                            url: String,
                            encryptedFileInfo: EncryptedFileInfo?,
                            thumbnailUrl: String? = null,
                            thumbnailEncryptedFileInfo: EncryptedFileInfo?,
                            newImageAttributes: NewImageAttributes?): Event {
        val messageContent: MessageContent = event.content.toModel() ?: return event
        val updatedContent = when (messageContent) {
            is MessageImageContent -> messageContent.update(url, encryptedFileInfo, newImageAttributes)
            is MessageVideoContent -> messageContent.update(url, encryptedFileInfo, thumbnailUrl, thumbnailEncryptedFileInfo)
            is MessageFileContent  -> messageContent.update(url, encryptedFileInfo)
            is MessageAudioContent -> messageContent.update(url, encryptedFileInfo)
            else                   -> messageContent
        }
        return event.copy(content = updatedContent.toContent())
    }

    private fun notifyTracker(params: Params, function: (String) -> Unit) {
        params.events
                .mapNotNull { it.eventId }
                .forEach { eventId -> function.invoke(eventId) }
    }

    private fun MessageImageContent.update(url: String,
                                           encryptedFileInfo: EncryptedFileInfo?,
                                           newImageAttributes: NewImageAttributes?): MessageImageContent {
        return copy(
                url = if (encryptedFileInfo == null) url else null,
                encryptedFileInfo = encryptedFileInfo?.copy(url = url),
                info = info?.copy(
                        width = newImageAttributes?.newWidth ?: info.width,
                        height = newImageAttributes?.newHeight ?: info.height,
                        size = newImageAttributes?.newFileSize ?: info.size
                )
        )
    }

    private fun MessageVideoContent.update(url: String,
                                           encryptedFileInfo: EncryptedFileInfo?,
                                           thumbnailUrl: String?,
                                           thumbnailEncryptedFileInfo: EncryptedFileInfo?): MessageVideoContent {
        return copy(
                url = if (encryptedFileInfo == null) url else null,
                encryptedFileInfo = encryptedFileInfo?.copy(url = url),
                videoInfo = videoInfo?.copy(
                        thumbnailUrl = if (thumbnailEncryptedFileInfo == null) thumbnailUrl else null,
                        thumbnailFile = thumbnailEncryptedFileInfo?.copy(url = thumbnailUrl)
                )
        )
    }

    private fun MessageFileContent.update(url: String,
                                          encryptedFileInfo: EncryptedFileInfo?): MessageFileContent {
        return copy(
                url = if (encryptedFileInfo == null) url else null,
                encryptedFileInfo = encryptedFileInfo?.copy(url = url)
        )
    }

    private fun MessageAudioContent.update(url: String,
                                           encryptedFileInfo: EncryptedFileInfo?): MessageAudioContent {
        return copy(
                url = if (encryptedFileInfo == null) url else null,
                encryptedFileInfo = encryptedFileInfo?.copy(url = url)
        )
    }

    companion object {
        private const val MAX_IMAGE_SIZE = 640
    }
}
