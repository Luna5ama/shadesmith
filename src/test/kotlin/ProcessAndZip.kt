import dev.luna5ama.shadesmith.Main
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun main() {
    val shadesmithOutputStr = "R:\\shadesmitth"
    val shadesmithOutput = File(shadesmithOutputStr)
    val args = arrayOf(
        "I:\\code\\mcshaders\\Alpha-Piscium\\shaders",
        "$shadesmithOutputStr\\shaders"
    )
    Main.main(args)

    val included = setOf("changelogs", "licenses", "shaders/lang", "shaders/textures", "LICENSE", "README.md")
    val rootDir = File("""I:\code\mcshaders\Alpha-Piscium""")
    val branchName = ProcessBuilder()
        .command("git", "rev-parse", "--abbrev-ref", "HEAD")
        .directory(rootDir)
        .start()
        .inputStream.bufferedReader().readText().trim()
    val commitTag = ProcessBuilder()
        .command("git", "rev-parse", "--short", "HEAD")
        .directory(rootDir)
        .start()
        .inputStream.bufferedReader().readText().trim()

    val outputZipFile = File(rootDir, "${rootDir.name.replace("-", " ")} $branchName $commitTag.zip")
    ZipOutputStream(
        outputZipFile.outputStream(),
        Charsets.UTF_8
    ).use { zipOut ->
        zipOut.setLevel(Deflater.DEFAULT_COMPRESSION)
        zipOut.setMethod(ZipOutputStream.DEFLATED)

        fun addStuff(rootDir: File, sequence: Sequence<File>) {
            sequence
                .filter { it != rootDir }
                .forEach { file ->
                    val relativePath = file.relativeTo(rootDir).invariantSeparatorsPath
                    if (file.isDirectory) {
                        if (file.listFiles()?.isNotEmpty() == true) {
                            zipOut.putNextEntry(ZipEntry("$relativePath/"))
                            zipOut.closeEntry()
                        }
                        return@forEach
                    }
                    zipOut.putNextEntry(ZipEntry(relativePath))
                    file.inputStream().use { input ->
                        input.copyTo(zipOut)
                    }
                    zipOut.closeEntry()
                }
        }

        addStuff(rootDir, rootDir.walk().filter { file ->
            if (file.extension == "properties") return@filter true
            val baseDirName = file.relativeTo(rootDir).path.replace(File.separator, "/")
            if (baseDirName.startsWith('.')) return@filter false
            included.any {
                baseDirName.startsWith(it)
            }
        })
        addStuff(shadesmithOutput, shadesmithOutput.walk())
    }
}