package com.mikepenz.aboutlibraries.plugin.util

import com.mikepenz.aboutlibraries.plugin.mapping.License
import com.mikepenz.aboutlibraries.plugin.mapping.SpdxLicense
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.FileNotFoundException
import java.net.URL

object LicenseUtil {
    private val LOGGER: Logger = LoggerFactory.getLogger(LicenseUtil::class.java)

    /** What each requested URL came to, failures included, so a URL is requested only once. */
    private val remoteLicenseCache = HashMap<String, RemoteText>()

    private sealed interface RemoteText {
        data class Found(val text: String) : RemoteText

        /** The server answered that it has no text there: SPDX has none for some licenses [SpdxLicense] knows. */
        data object Missing : RemoteText

        /** The request failed without that answer: no network, a refused or timed-out connection, an error status. */
        data object Unreachable : RemoteText
    }

    internal fun loadLicenseCached(url: String): String? {
        val remoteText = remoteLicenseCache.getOrPut(url) {
            try {
                // cleanup empty lines before and after, and any indent.
                RemoteText.Found(URL(url).readText().trimIndent())
            } catch (e: FileNotFoundException) {
                RemoteText.Missing
            } catch (t: Throwable) {
                RemoteText.Unreachable
            }
        }
        return (remoteText as? RemoteText.Found)?.text?.takeIf { it.isNotBlank() }
    }

    /** `true` when a request for [url] failed without the server answering that there is no text. */
    internal fun isUnreachable(url: String): Boolean = remoteLicenseCache[url] == RemoteText.Unreachable

    /**
     * `true` when this license has no text because its SPDX text couldn't be downloaded, rather than
     * because SPDX has none.
     */
    internal fun License.isSpdxTextUnreachable(): Boolean {
        if (!content.isNullOrBlank()) return false
        val enumLicense = spdxId?.let { SpdxLicense.find(it) } ?: return false
        return isUnreachable(enumLicense.getTxtUrl()) || isUnreachable(enumLicense.getFallbackTxtUrl())
    }

    fun License.loadSpdxLicense(mapToSpdx: Boolean = true) {
        val spdxId = spdxId ?: return
        try {
            val enumLicense = SpdxLicense.find(spdxId)
            if (enumLicense != null) {
                val singleLicense: String? = loadLicenseCached(enumLicense.getTxtUrl()) ?: loadLicenseCached(enumLicense.getFallbackTxtUrl())
                if (singleLicense?.isNotBlank() == true) {
                    if (mapToSpdx || name.isBlank()) {
                        name = enumLicense.fullName
                    }
                    if (mapToSpdx || url.isNullOrBlank()) {
                        url = enumLicense.getUrl()
                    }
                    content = singleLicense
                }
            } else {
                LOGGER.info("`spdxId` did not match any known SpdxLicense: $spdxId")
            }
        } catch (t: Throwable) {
            LOGGER.debug("Could not load the license content", t)
        }
    }

    fun HashSet<License>.findSameSpdx(spdxId: String?): License? {
        spdxId ?: return null
        return firstOrNull { it.spdxId == spdxId }
    }
}

fun License.merge(with: License) {
    val orgLic = this
    with.name.takeIf { it.isNotBlank() }?.also { orgLic.name = it }
    with.url?.takeIf { it.isNotBlank() }?.also { orgLic.url = it }
    with.year?.takeIf { it.isNotBlank() }?.also { orgLic.year = it }
    with.content?.takeIf { it.isNotBlank() }?.also { orgLic.content = it }
}