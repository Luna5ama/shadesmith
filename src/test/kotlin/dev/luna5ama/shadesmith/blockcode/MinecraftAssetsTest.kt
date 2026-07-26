package dev.luna5ama.shadesmith.blockcode

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinecraftAssetsTest {
    @Test
    fun `acquires verified latest release`() = withCache { cacheRoot ->
        Fixture().use { fixture ->
            val assets = fixture.acquire(cacheRoot)
            assertEquals(fixture.version, assets.versionId)
            assertEquals(fixture.metadataUri, assets.versionMetadata.uri)
            assertEquals(fixture.metadataSha1, assets.versionMetadata.sha1)
            assertEquals(fixture.clientSha1, assets.clientSha1)
            assertEquals(fixture.clientBytes.size.toLong(), assets.clientSize)
            assertEquals(fixture.clientBytes.toList(), assets.clientJar.readBytes().toList())
        }
    }

    @Test
    fun `uses a verified warm cache`() = withCache { cacheRoot ->
        Fixture().use { fixture ->
            val first = fixture.acquire(cacheRoot)
            val second = fixture.acquire(cacheRoot)
            assertEquals(first, second)
            assertEquals(2, fixture.manifestRequests)
            assertEquals(2, fixture.metadataRequests)
            assertEquals(1, fixture.clientRequests)
        }
    }

    @Test
    fun `replaces a corrupt cached client`() = withCache { cacheRoot ->
        Fixture().use { fixture ->
            val first = fixture.acquire(cacheRoot)
            Files.write(first.clientJar, ByteArray(fixture.clientBytes.size) { 0x7f })
            val second = fixture.acquire(cacheRoot)
            assertEquals(2, fixture.clientRequests)
            assertEquals(fixture.clientBytes.toList(), second.clientJar.readBytes().toList())
        }
    }

    @Test
    fun `rejects version metadata with the wrong SHA1`() = withCache { cacheRoot ->
        Fixture().use { fixture ->
            fixture.metadataSha1Override = "0".repeat(40)
            assertFails { fixture.acquire(cacheRoot) }
            assertEquals(0, fixture.clientRequests)
            assertNoCachedFiles(cacheRoot)
        }
    }

    @Test
    fun `rejects client with the wrong SHA1`() = withCache { cacheRoot ->
        Fixture().use { fixture ->
            fixture.declaredClientSha1 = "0".repeat(40)
            assertFails { fixture.acquire(cacheRoot) }
            assertNoCachedFiles(cacheRoot)
        }
    }

    @Test
    fun `rejects client with the wrong size`() = withCache { cacheRoot ->
        Fixture().use { fixture ->
            fixture.declaredClientSize = fixture.clientBytes.size.toLong() + 1
            assertFails { fixture.acquire(cacheRoot) }
            assertNoCachedFiles(cacheRoot)
        }
    }

    @Test
    fun `rejects non successful metadata responses`() = withCache { cacheRoot ->
        Fixture().use { fixture ->
            fixture.metadataStatus = 503
            assertFails { fixture.acquire(cacheRoot) }
            assertEquals(0, fixture.clientRequests)
            assertNoCachedFiles(cacheRoot)
        }
    }

    @Test
    fun `rejects non successful client responses without partial cache`() = withCache { cacheRoot ->
        Fixture().use { fixture ->
            fixture.clientStatus = 503
            assertFails { fixture.acquire(cacheRoot) }
            assertEquals(1, fixture.clientRequests)
            assertNoCachedFiles(cacheRoot)
        }
    }

    @Test
    fun `rejects malformed version metadata`() = withCache { cacheRoot ->
        Fixture().use { fixture ->
            fixture.metadataBytesOverride = "{".toByteArray()
            assertFails { fixture.acquire(cacheRoot) }
            assertEquals(0, fixture.clientRequests)
            assertNoCachedFiles(cacheRoot)
        }
    }

    @Test
    fun `does not fall back to a stale cached release`() = withCache { cacheRoot ->
        Fixture().use { fixture ->
            val cached = fixture.acquire(cacheRoot).clientJar
            fixture.manifestStatus = 503
            assertFails { fixture.acquire(cacheRoot) }
            assertTrue(Files.exists(cached))
            assertEquals(1, fixture.clientRequests)
        }
    }

    private fun withCache(test: (Path) -> Unit) {
        val cacheRoot = Files.createTempDirectory("shadesmith-assets-test-")
        try {
            test(cacheRoot)
        } finally {
            Files.walk(cacheRoot).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
        assertFalse(Files.exists(cacheRoot))
    }

    private fun assertNoCachedFiles(cacheRoot: Path) {
        Files.walk(cacheRoot).use { paths -> assertTrue(paths.noneMatch(Files::isRegularFile)) }
    }

    private class Fixture : AutoCloseable {
        val version = "1.21-test"
        val clientBytes = zipBytes()
        val clientSha1 = sha1(clientBytes)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val baseUri = URI("http://127.0.0.1:${server.address.port}")
        val metadataUri: URI = baseUri.resolve("/version.json")
        private val clientUri: URI = baseUri.resolve("/client.jar")
        private val httpClient = HttpClient.newHttpClient()
        var manifestStatus = 200
        var metadataStatus = 200
        var clientStatus = 200
        var metadataBytesOverride: ByteArray? = null
        var metadataSha1Override: String? = null
        var declaredClientSha1 = clientSha1
        var declaredClientSize = clientBytes.size.toLong()
        var manifestRequests = 0
        var metadataRequests = 0
        var clientRequests = 0
        val metadataSha1: String
            get() = metadataSha1Override ?: sha1(metadataBytes())

        init {
            server.createContext("/manifest.json") { exchange ->
                manifestRequests++
                respond(exchange, manifestStatus, manifestJson().toByteArray())
            }
            server.createContext("/version.json") { exchange ->
                metadataRequests++
                respond(exchange, metadataStatus, metadataBytes())
            }
            server.createContext("/client.jar") { exchange ->
                clientRequests++
                respond(exchange, clientStatus, clientBytes)
            }
            server.start()
        }

        fun acquire(cacheRoot: Path): MinecraftAssets = acquireLatestMinecraftAssets(
            cacheRoot = cacheRoot,
            manifestUri = baseUri.resolve("/manifest.json"),
            client = httpClient
        )

        override fun close() = server.stop(0)

        private fun manifestJson() = """
            {
              "latest": { "release": "$version" },
              "versions": [
                { "id": "$version", "url": "$metadataUri", "sha1": "$metadataSha1" }
              ]
            }
        """.trimIndent()

        private fun metadataBytes() = metadataBytesOverride ?: """
            {
              "id": "$version",
              "downloads": {
                "client": {
                  "url": "$clientUri",
                  "sha1": "$declaredClientSha1",
                  "size": $declaredClientSize
                }
              }
            }
        """.trimIndent().toByteArray()
    }

    private companion object {
        fun respond(exchange: HttpExchange, status: Int, bytes: ByteArray) {
            exchange.sendResponseHeaders(status, if (status in 200..299) bytes.size.toLong() else -1)
            if (status in 200..299) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }

        fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

        fun zipBytes(): ByteArray = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("assets/example.txt"))
                zip.write("asset".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
    }
}
