/*
 * Copyright (c) 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.riotx.features.crypto.recover

import im.vector.matrix.android.api.NoOpMatrixCallback
import im.vector.matrix.android.api.listeners.ProgressListener
import im.vector.matrix.android.api.session.Session
import im.vector.matrix.android.api.session.crypto.crosssigning.KEYBACKUP_SECRET_SSSS_NAME
import im.vector.matrix.android.api.session.securestorage.EmptyKeySigner
import im.vector.matrix.android.api.session.securestorage.RawBytesKeySpec
import im.vector.matrix.android.api.session.securestorage.SharedSecretStorageService
import im.vector.matrix.android.api.session.securestorage.SsssKeyCreationInfo
import im.vector.matrix.android.internal.crypto.crosssigning.toBase64NoPadding
import im.vector.matrix.android.internal.crypto.keysbackup.deriveKey
import im.vector.matrix.android.internal.crypto.keysbackup.util.computeRecoveryKey
import im.vector.matrix.android.internal.crypto.keysbackup.util.extractCurveKeyFromRecoveryKey
import im.vector.matrix.android.internal.util.awaitCallback
import im.vector.riotx.R
import im.vector.riotx.core.platform.ViewModelTask
import im.vector.riotx.core.platform.WaitingViewData
import im.vector.riotx.core.resources.StringProvider
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

class BackupToQuadSMigrationTask @Inject constructor(
        val session: Session,
        val stringProvider: StringProvider
) : ViewModelTask<BackupToQuadSMigrationTask.Params, BackupToQuadSMigrationTask.Result> {

    sealed class Result {
        object Success : Result()
        abstract class Failure(val throwable: Throwable?) : Result()
        object InvalidRecoverySecret : Failure(null)
        object NoKeyBackupVersion : Failure(null)
        object IllegalParams : Failure(null)
        class ErrorFailure(throwable: Throwable) : Failure(throwable)
    }

    data class Params(
            val passphrase: String?,
            val recoveryKey: String?,
            val progressListener: BootstrapProgressListener? = null
    )

    override suspend fun execute(params: Params): Result {
        try {
            // We need to use the current secret for keybackup and use it as the new master key for SSSS
            // Then we need to put back the backup key in sss
            val keysBackupService = session.cryptoService().keysBackupService()
            val quadS = session.sharedSecretStorageService

            val version = keysBackupService.keysBackupVersion ?: return Result.NoKeyBackupVersion

            reportProgress(params, R.string.bootstrap_progress_checking_backup)
            val curveKey =
                    (if (params.recoveryKey != null) {
                        extractCurveKeyFromRecoveryKey(params.recoveryKey)
                    } else if (!params.passphrase.isNullOrEmpty() && version.getAuthDataAsMegolmBackupAuthData()?.privateKeySalt != null) {
                        version.getAuthDataAsMegolmBackupAuthData()?.let { authData ->
                            deriveKey(params.passphrase, authData.privateKeySalt!!, authData.privateKeyIterations!!, object : ProgressListener {
                                override fun onProgress(progress: Int, total: Int) {
                                    params.progressListener?.onProgress(WaitingViewData(
                                            stringProvider.getString(R.string.bootstrap_progress_checking_backup_with_info,
                                                    "$progress/$total")
                                    ))
                                }
                            })
                        }
                    } else null)
                            ?: return Result.IllegalParams

            reportProgress(params, R.string.bootstrap_progress_compute_curve_key)
            val recoveryKey = computeRecoveryKey(curveKey)

            val isValid = awaitCallback<Boolean> {
                keysBackupService.isValidRecoveryKeyForCurrentVersion(recoveryKey, it)
            }

            if (!isValid) return Result.InvalidRecoverySecret

            val info: SsssKeyCreationInfo =
                    when {
                        params.passphrase?.isNotEmpty() == true -> {
                            reportProgress(params, R.string.bootstrap_progress_generating_ssss)
                            awaitCallback<SsssKeyCreationInfo> {
                                quadS.generateKeyWithPassphrase(
                                        UUID.randomUUID().toString(),
                                        "ssss_key",
                                        params.passphrase,
                                        EmptyKeySigner(),
                                        object : ProgressListener {
                                            override fun onProgress(progress: Int, total: Int) {
                                                params.progressListener?.onProgress(
                                                        WaitingViewData(
                                                                stringProvider.getString(
                                                                        R.string.bootstrap_progress_generating_ssss_with_info,
                                                                        "$progress/$total")
                                                        ))
                                            }
                                        },
                                        it
                                )
                            }
                        }
                        params.recoveryKey != null              -> {
                            reportProgress(params, R.string.bootstrap_progress_generating_ssss_recovery)
                            awaitCallback {
                                quadS.generateKey(
                                        UUID.randomUUID().toString(),
                                        extractCurveKeyFromRecoveryKey(params.recoveryKey)?.let { RawBytesKeySpec(it) },
                                        "ssss_key",
                                        EmptyKeySigner(),
                                        it
                                )
                            }
                        }
                        else                                    -> {
                            return Result.IllegalParams
                        }
                    }

            // Ok, so now we have migrated the old keybackup secret as the quadS key
            // Now we need to store the keybackup key in SSSS in a compatible way
            reportProgress(params, R.string.bootstrap_progress_storing_in_sss)
            awaitCallback<Unit> {
                quadS.storeSecret(
                        KEYBACKUP_SECRET_SSSS_NAME,
                        curveKey.toBase64NoPadding(),
                        listOf(SharedSecretStorageService.KeyRef(info.keyId, info.keySpec)),
                        it
                )
            }

            // save for gossiping
            keysBackupService.saveBackupRecoveryKey(recoveryKey, version.version)

            // while we are there let's restore, but do not block
            session.cryptoService().keysBackupService().restoreKeysWithRecoveryKey(
                    version,
                    recoveryKey,
                    null,
                    null,
                    null,
                    NoOpMatrixCallback()
            )

            return Result.Success
        } catch (failure: Throwable) {
            Timber.e(failure, "## BackupToQuadSMigrationTask - Failed to migrate backup")
            return Result.ErrorFailure(failure)
        }
    }

    private fun reportProgress(params: Params, stringRes: Int) {
        params.progressListener?.onProgress(WaitingViewData(stringProvider.getString(stringRes)))
    }
}
