package com.hawkeynl.jarvis.update

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.hawkeynl.jarvis.network.AndroidUpdateMetadata
import com.hawkeynl.jarvis.network.ApiResult
import com.hawkeynl.jarvis.network.HomeNodeEndpoint
import com.hawkeynl.jarvis.network.JarvisApi
import com.hawkeynl.jarvis.storage.SessionRepository
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface AndroidUpdateCheck {
    data object Unauthorized : AndroidUpdateCheck
    data object Current : AndroidUpdateCheck
    data class Available(val metadata: AndroidUpdateMetadata) : AndroidUpdateCheck
    data class Failed(val message: String) : AndroidUpdateCheck
}

sealed interface AndroidUpdateDownload {
    data class Ready(val versionName: String) : AndroidUpdateDownload
    data object Unauthorized : AndroidUpdateDownload
    data class Failed(val message: String) : AndroidUpdateDownload
}

sealed interface InstallerHandoff {
    data object Started : InstallerHandoff
    data object PermissionRequired : InstallerHandoff
    data class Failed(val message: String) : InstallerHandoff
}

class AndroidUpdateService(
    private val application: Application,
    private val api: JarvisApi,
    private val sessions: SessionRepository,
) {
    private var pending: PendingApk? = null

    suspend fun check(endpoint: HomeNodeEndpoint): AndroidUpdateCheck {
        val token = sessions.session().token ?: return AndroidUpdateCheck.Unauthorized
        val installedSigner = installedSigningCertificateSha256()
            ?: return AndroidUpdateCheck.Failed("The signature of the installed Jarvis app cannot be read.")
        return when (val result = api.androidUpdate(
            endpoint,
            token,
            com.hawkeynl.jarvis.BuildConfig.VERSION_CODE,
            ANDROID_CLIENT_PROTOCOL,
        )) {
            is ApiResult.Success -> when (val decision = AndroidUpdatePolicy.evaluate(
                endpoint,
                com.hawkeynl.jarvis.BuildConfig.VERSION_CODE,
                installedSigner,
                result.value,
            )) {
                AndroidUpdateDecision.Current -> AndroidUpdateCheck.Current
                is AndroidUpdateDecision.Available -> AndroidUpdateCheck.Available(decision.metadata)
                is AndroidUpdateDecision.Invalid -> AndroidUpdateCheck.Failed(decision.reason)
            }
            ApiResult.Unauthorized -> AndroidUpdateCheck.Unauthorized
            is ApiResult.HttpError -> AndroidUpdateCheck.Failed(
                result.message ?: "Home Node refused the Android update check (${result.status}).",
            )
            is ApiResult.InvalidResponse -> AndroidUpdateCheck.Failed(result.message)
            is ApiResult.Unreachable -> AndroidUpdateCheck.Failed("Home Node is unreachable for updates.")
        }
    }

    suspend fun download(endpoint: HomeNodeEndpoint, metadata: AndroidUpdateMetadata): AndroidUpdateDownload {
        val token = sessions.session().token ?: return AndroidUpdateDownload.Unauthorized
        val directory = File(application.cacheDir, "app-updates").apply { mkdirs() }
        val temporary = File(directory, ".Jarvis-update.apk.part")
        val verified = File(directory, "Jarvis-update.apk")
        temporary.delete()
        pending = null
        return when (val result = api.downloadAndroidUpdate(
            endpoint,
            token,
            temporary,
            metadata.artifact.size,
        )) {
            is ApiResult.Success -> {
                val error = withContext(Dispatchers.IO) {
                    verifyDownloadedApk(temporary, metadata)
                }
                if (error != null) {
                    temporary.delete()
                    AndroidUpdateDownload.Failed(error)
                } else if ((verified.exists() && !verified.delete()) || !temporary.renameTo(verified)) {
                    temporary.delete()
                    AndroidUpdateDownload.Failed("The verified APK could not be prepared.")
                } else {
                    pending = PendingApk(verified)
                    AndroidUpdateDownload.Ready(metadata.version_name)
                }
            }
            ApiResult.Unauthorized -> AndroidUpdateDownload.Unauthorized
            is ApiResult.HttpError -> AndroidUpdateDownload.Failed(
                result.message ?: "Home Node refused the APK download (${result.status}).",
            )
            is ApiResult.InvalidResponse -> AndroidUpdateDownload.Failed(result.message)
            is ApiResult.Unreachable -> AndroidUpdateDownload.Failed("APK download interrupted: Home Node is unreachable.")
        }
    }

    fun handOffToPackageInstaller(activity: Activity): InstallerHandoff {
        val update = pending ?: return InstallerHandoff.Failed("Download and verify the update first.")
        if (!application.packageManager.canRequestPackageInstalls()) {
            return try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${application.packageName}"),
                    ),
                )
                InstallerHandoff.PermissionRequired
            } catch (_: RuntimeException) {
                InstallerHandoff.Failed("Android could not open the install permission screen.")
            }
        }
        return try {
            val uri = FileProvider.getUriForFile(
                application,
                "${application.packageName}.update-files",
                update.file,
            )
            activity.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
            InstallerHandoff.Started
        } catch (_: RuntimeException) {
            InstallerHandoff.Failed("Android could not open the package installer screen.")
        }
    }

    private fun verifyDownloadedApk(file: File, metadata: AndroidUpdateMetadata): String? {
        if (!file.isFile || file.length() != metadata.artifact.size) {
            return "The APK download is incomplete."
        }
        if (file.sha256() != metadata.artifact.sha256) {
            return "The APK checksum does not match."
        }
        val info = application.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: return "Android does not recognise the downloaded file as an APK."
        if (info.packageName != JARVIS_ANDROID_PACKAGE || info.longVersionCode != metadata.version_code.toLong() ||
            info.versionName != metadata.version_name
        ) {
            return "APK package or version does not match the update metadata."
        }
        val archiveSigner = info.signingInfo?.apkContentsSigners?.singleOrNull()?.toByteArray()?.sha256()
            ?: return "The APK has no unambiguous signing identity."
        val installedSigner = installedSigningCertificateSha256()
            ?: return "The installed signing identity is unavailable."
        if (archiveSigner != installedSigner || archiveSigner != metadata.artifact.signing_certificate_sha256) {
            return "APK signature does not match the installed Jarvis app."
        }
        return null
    }

    private fun installedSigningCertificateSha256(): String? = runCatching {
        application.packageManager.getPackageInfo(
            application.packageName,
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES,
        ).signingInfo?.apkContentsSigners?.singleOrNull()?.toByteArray()?.sha256()
    }.getOrNull()

    private data class PendingApk(val file: File)
}

private fun File.sha256(): String = inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    digest.digest().toHex()
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
