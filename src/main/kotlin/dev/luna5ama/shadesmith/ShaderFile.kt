package dev.luna5ama.shadesmith

import java.nio.file.Path

data class ShaderFile(val path: Path, val code: String, val compositeStyle: Boolean = false)