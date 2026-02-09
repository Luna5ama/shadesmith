package dev.luna5ama.shadesmith.blockcode

import dev.luna5ama.shadesmith.TextureFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

object PBRAssemblerImpl : PBRAssembler {
    private fun buildByteData(byteSize: Int, block: ByteBuffer.() -> Unit): ByteArray {
        val buffer = ByteBuffer.allocate(byteSize).order(ByteOrder.nativeOrder())
        block(buffer)
        return buffer.array()
    }
    override fun BlockScope.provide(): Sequence<Pair<BlockState, List<LUTData>>> = sequence {
        val sss = SSS.provide()
        val emissive = Emissive.provide()
        val ior = IOR.provide()
        val water = WaterFlag.provide()
        val allStates = sss.keys + emissive.keys + ior.keys + water.keys
        fun <P : PBRValue<*, *>> getData(provider: PBRProvider<P>, map: Map<BlockState, P>, state: BlockState): P {
            return map[state] ?: map[baseState] ?: provider.defaultValue
        }
        for (state in allStates) {
            val sssValue = getData(SSS, sss, state)
            val emissiveValue = getData(Emissive, emissive, state)
            val iorValue = getData(IOR, ior, state)
            val waterValue = getData(WaterFlag, water, state)
            yield(state to buildList {
                add(LUTData(
                    TextureFormat.R32UI,
                    buildByteData(4) {
                        var int32Bits = 0
                        int32Bits = int32Bits or (sssValue.rawData.toInt() and 0xF)
                        int32Bits = int32Bits or ((emissiveValue.rawData.toInt() and 0xF) shl 4)
                        int32Bits = int32Bits or ((iorValue.rawData.toInt() and 0xFF) shl 8)
                        int32Bits = int32Bits or ((waterValue.rawData.toInt() and 0x1) shl 16)
                        putInt(int32Bits)
                    }
                ))
            })
        }
    }
}