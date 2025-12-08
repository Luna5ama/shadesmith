package dev.luna5ama.shadesmith

data class ShaderFile(val fileName: String, val fileDir: String, val code: String) {
    val filePath get() = "$fileDir/$fileName"
}