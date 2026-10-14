package info.cemu.cemu.graphicpacks

import info.cemu.cemu.BuildConfig
import info.cemu.cemu.common.io.unzip
import info.cemu.cemu.nativeinterface.NativeGraphicPacks
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import kotlin.io.path.div
import kotlin.io.path.readText
import kotlin.time.Clock

enum class GraphicPacksDownloadStatus {
    CHECKING_VERSION,
    NO_UPDATES_AVAILABLE,
    DOWNLOADING,
    EXTRACTING,
    FINISHED_DOWNLOADING,
    ERROR,
}

@Serializable
data class Release(
    val name: String,
    val assets: List<Asset>
)

@Serializable
data class Asset(
    @SerialName("browser_download_url")
    val browserDownloadUrl: String
)

class GraphicPacksDownloader(private val client: HttpClient = HttpClient()) {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    companion object {
        // Version of the graphic packs zip bundled in the APK assets
        const val BUNDLED_VERSION = "Github987"
        private const val RELEASES_ATOM_URL = "https://github.com/cemu-project/cemu_graphic_packs/releases.atom"
    }

    private fun getCurrentVersion(graphicPacksDir: File): String? {
        val graphicPacksVersionFile =
            graphicPacksDir.toPath() / "downloadedGraphicPacks" / "version.txt"
        return try {
            graphicPacksVersionFile.readText()
        } catch (_: IOException) {
            null
        }
    }

    private suspend fun getUpdateUrl(): String {
        val queryUrl = "https://cemu.info/api2/query_graphicpack_url.php?" +
                "version=${BuildConfig.VERSION_NAME}" +
                "&t=${Clock.System.now().toEpochMilliseconds()}"

        val response = client.get(queryUrl)
        val body = response.body<String>().trim()

        if (response.status.isSuccess() && body.startsWith("http")) {
            return body
        }

        return "https://api.github.com/repos/cemu-project/cemu_graphic_packs/releases/latest"
    }

    private suspend fun fetchLatestRelease(): Release? {
        // primary path: cemu.info api2 -> GitHub API, then fall back to the
        // releases atom feed when the GitHub API is rate limited or unreachable
        return fetchLatestReleaseFromApi() ?: fetchLatestReleaseFromAtom()
    }

    private suspend fun fetchLatestReleaseFromApi(): Release? {
        val response = client.get(getUpdateUrl())

        if (!response.status.isSuccess()) {
            return null
        }

        return try {
            json.decodeFromString<Release>(response.body())
        } catch (_: Exception) {
            null
        }
    }

    // The GitHub REST API is heavily rate limited on shared IP addresses. The
    // releases atom feed is served without those limits and exposes the latest
    // release tag, from which the asset URL can be constructed.
    private suspend fun fetchLatestReleaseFromAtom(): Release? {
        return try {
            val response = client.get(RELEASES_ATOM_URL)

            if (!response.status.isSuccess()) {
                return null
            }

            val body = response.body<String>()
            val versionRegex = Regex("Github(\\d+)")
            val latestVersion = versionRegex.findAll(body).firstOrNull()?.groupValues?.get(1)
                ?: return null

            Release(
                name = "Github$latestVersion",
                assets = listOf(
                    Asset(browserDownloadUrl = "https://github.com/cemu-project/cemu_graphic_packs/releases/download/Github$latestVersion/graphicPacks$latestVersion.zip")
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun isUpToDate(dir: File, newVersion: String): Boolean {
        return getCurrentVersion(dir) == newVersion
    }

    private suspend fun downloadZip(url: String): ByteArray? {
        val response = client.get(url)

        if (!response.status.isSuccess()) {
            return null
        }

        return response.bodyAsBytes()
    }

    private suspend fun extractAndInstall(
        zipBytes: ByteArray,
        graphicPacksDir: File,
        version: String
    ) = withContext(Dispatchers.IO) {

        val tempDir = graphicPacksDir.resolve("downloadedGraphicPacksTemp")
        val finalDir = graphicPacksDir.resolve("downloadedGraphicPacks")

        tempDir.deleteRecursively()

        unzip(zipBytes.inputStream(), tempDir.path)

        tempDir.resolve("version.txt").writeText(version)

        finalDir.deleteRecursively()
        tempDir.renameTo(finalDir)

        NativeGraphicPacks.refreshGraphicPacks()
    }

    fun download(graphicPacksRootDir: File): Flow<GraphicPacksDownloadStatus> = flow {
        val graphicPacksDir = graphicPacksRootDir.resolve("graphicPacks")

        emit(GraphicPacksDownloadStatus.CHECKING_VERSION)

        val release = fetchLatestRelease() ?: run {
            emit(GraphicPacksDownloadStatus.ERROR)
            return@flow
        }

        val version = release.name

        if (isUpToDate(graphicPacksDir, version)) {
            emit(GraphicPacksDownloadStatus.NO_UPDATES_AVAILABLE)
            return@flow
        }

        val downloadUrl = release.assets.firstOrNull()?.browserDownloadUrl ?: run {
            emit(GraphicPacksDownloadStatus.ERROR)
            return@flow
        }

        emit(GraphicPacksDownloadStatus.DOWNLOADING)

        val zipBytes = downloadZip(downloadUrl) ?: run {
            emit(GraphicPacksDownloadStatus.ERROR)
            return@flow
        }

        emit(GraphicPacksDownloadStatus.EXTRACTING)

        extractAndInstall(zipBytes, graphicPacksDir, version)

        emit(GraphicPacksDownloadStatus.FINISHED_DOWNLOADING)
    }

    // Installs the graphic packs zip bundled in the APK assets. Works fully offline
    // and includes the official 60FPS patches for many games.
    fun installBundled(graphicPacksRootDir: File, assetStreamProvider: () -> java.io.InputStream?): Flow<GraphicPacksDownloadStatus> = flow {
        emit(GraphicPacksDownloadStatus.EXTRACTING)

        val zipBytes = withContext(Dispatchers.IO) {
            try {
                assetStreamProvider()?.use { it.readBytes() }
            } catch (_: Exception) {
                null
            }
        } ?: run {
            emit(GraphicPacksDownloadStatus.ERROR)
            return@flow
        }

        extractAndInstall(zipBytes, graphicPacksRootDir.resolve("graphicPacks"), BUNDLED_VERSION)

        emit(GraphicPacksDownloadStatus.FINISHED_DOWNLOADING)
    }
}
