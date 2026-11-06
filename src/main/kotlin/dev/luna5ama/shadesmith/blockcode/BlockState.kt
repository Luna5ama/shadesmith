package dev.luna5ama.shadesmith.blockcode

data class BlockState(val blockID: String, val states: Map<String, String> = emptyMap())
