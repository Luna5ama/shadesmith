package dev.luna5ama.shadesmith.blockcode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat

internal data class MinecraftVersionMetadataProvenance(
    val uri: URI,
    val sha1: String
)

internal data class MinecraftAssets(
    val versionId: String,
    val versionMetadata: MinecraftVersionMetadataProvenance,
    val clientSha1: String,
    val clientSize: Long,
    val clientJar: Path
)

internal fun acquireLatestMinecraftAssets(
    cacheRoot: Path = Path.of(System.getProperty("user.home"), ".cache", "shadesmith", "minecraft"),
    manifestUri: URI = URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"),
    client: HttpClient = HttpClient.newHttpClient()
): MinecraftAssets {
    val manifest = Json.parseToJsonElement(fetchBytes(client, manifestUri).decodeToString()).jsonObject
    val versionId = manifest.getValue("latest").jsonObject.getValue("release").jsonPrimitive.content
    require(versionId.matches(Regex("[A-Za-z0-9._-]+")) && versionId != "." && versionId != "..") {
        "Unsafe Minecraft version id: $versionId"
    }

    val versionEntry = manifest.getValue("versions").jsonArray
        .map { it.jsonObject }
        .firstOrNull { it.getValue("id").jsonPrimitive.content == versionId }
        ?: error("Latest Minecraft release $versionId is absent from the version manifest")
    val metadataUri = URI.create(versionEntry.getValue("url").jsonPrimitive.content)
    val metadataSha1 = versionEntry.getValue("sha1").jsonPrimitive.content
    requireSha1(metadataSha1, "version metadata")

    val metadataBytes = fetchBytes(client, metadataUri)
    check(sha1(metadataBytes).equals(metadataSha1, ignoreCase = true)) {
        "Minecraft version metadata SHA1 mismatch"
    }
    val metadata = Json.parseToJsonElement(metadataBytes.decodeToString()).jsonObject
    check(metadata.getValue("id").jsonPrimitive.content == versionId) {
        "Minecraft version metadata id does not match $versionId"
    }

    val clientDownload = metadata.getValue("downloads").jsonObject.getValue("client").jsonObject
    val clientUri = URI.create(clientDownload.getValue("url").jsonPrimitive.content)
    val clientSha1 = clientDownload.getValue("sha1").jsonPrimitive.content
    val clientSize = clientDownload.getValue("size").jsonPrimitive.content.toLong()
    requireSha1(clientSha1, "client")
    require(clientSize >= 0) { "Minecraft client size must not be negative" }

    val clientJar = cacheRoot.resolve(versionId).resolve(clientSha1).resolve("client.jar")
    if (!verified(clientJar, clientSha1, clientSize)) {
        Files.deleteIfExists(clientJar)
        Files.createDirectories(clientJar.parent)
        val temporary = Files.createTempFile(clientJar.parent, "client-", ".tmp")
        try {
            download(client, clientUri, temporary)
            check(verified(temporary, clientSha1, clientSize)) {
                "Minecraft client SHA1 or size mismatch"
            }
            Files.move(
                temporary,
                clientJar,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    return MinecraftAssets(
        versionId = versionId,
        versionMetadata = MinecraftVersionMetadataProvenance(metadataUri, metadataSha1),
        clientSha1 = clientSha1,
        clientSize = clientSize,
        clientJar = clientJar
    )
}

private fun fetchBytes(client: HttpClient, uri: URI): ByteArray {
    val response = client.send(
        HttpRequest.newBuilder(uri).GET().build(),
        HttpResponse.BodyHandlers.ofByteArray()
    )
    check(response.statusCode() in 200..299) {
        "HTTP ${response.statusCode()} fetching $uri"
    }
    return response.body()
}

private fun download(client: HttpClient, uri: URI, target: Path) {
    val response = client.send(
        HttpRequest.newBuilder(uri).GET().build(),
        HttpResponse.BodyHandlers.ofFile(target)
    )
    check(response.statusCode() in 200..299) {
        "HTTP ${response.statusCode()} fetching $uri"
    }
}

private fun verified(path: Path, expectedSha1: String, expectedSize: Long): Boolean {
    return Files.isRegularFile(path) &&
        Files.size(path) == expectedSize &&
        sha1(path).equals(expectedSha1, ignoreCase = true)
}

private fun requireSha1(value: String, source: String) {
    require(value.matches(Regex("[0-9a-fA-F]{40}"))) { "Invalid $source SHA1" }
}

private fun sha1(bytes: ByteArray): String {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes))
}

private fun sha1(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-1")
    DigestInputStream(Files.newInputStream(path), digest).use {
        it.transferTo(OutputStream.nullOutputStream())
    }
    return HexFormat.of().formatHex(digest.digest())
}
