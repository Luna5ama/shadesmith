package dev.luna5ama.shadesmith.blockcode

import kotlin.math.pow
import kotlin.math.sqrt

object SSS : PBRProvider<PBRValue.UInt4> {
    override val defaultValue: PBRValue.UInt4 = PBRValue.UInt4(0u)
    override fun BlockScope.provide(): Sequence<Pair<BlockState, PBRValue.UInt4>> = sequence {
        if (property.tags.contains(BlockProperty.Tags.SmallFlower)) {
            yield(baseState to PBRValue.UInt4(15u))
        } else if (property.tags.contains(BlockProperty.Tags.Flower) && !nameEndsWith("_leaves")) {
            yield(baseState to PBRValue.UInt4(13u))
        }

        if (nameEndsWith("_sapling")) {
            yield(baseState to PBRValue.UInt4(12u))
        }

        if (nameEquals(BlockNames.ShortDryGrass, BlockNames.ShortGrass)) {
            yield(baseState to PBRValue.UInt4(14u))

            if (nameEquals(BlockNames.ShortGrass)) {
                yield(BlockState("grass") to PBRValue.UInt4(14u)) // Old name
            }
        }

        if (nameEquals(BlockNames.TallGrass, BlockNames.TallDryGrass)) {
            yield(baseState to PBRValue.UInt4(12u))
        }
        if (nameEndsWith("_leaves")) {
            yield(baseState to PBRValue.UInt4(10u))
        }
        if (nameContains("quartz") && !nameEquals(BlockNames.NetherQuartzOre)) {
            yield(baseState to PBRValue.UInt4(2u))
        }
    }
}

object SmallFoliageFlag: PBRProvider<PBRValue.Bool> {
    override val defaultValue: PBRValue.Bool = PBRValue.Bool(false)
    override fun BlockScope.provide(): Sequence<Pair<BlockState, PBRValue.Bool>> = sequence {
        if (property.tags.contains(BlockProperty.Tags.SmallFlower)) {
            yield(baseState to PBRValue.Bool(true))
        } else if (property.tags.contains(BlockProperty.Tags.Flower) && !nameEndsWith("_leaves")) {
            yield(baseState to PBRValue.Bool(true))
        }

        if (nameEndsWith("_sapling")) {
            yield(baseState to PBRValue.Bool(true))
        }

        if (nameEquals(BlockNames.ShortDryGrass, BlockNames.ShortGrass)) {
            yield(baseState to PBRValue.Bool(true))

            if (nameEquals(BlockNames.ShortGrass)) {
                yield(BlockState("grass") to PBRValue.Bool(true)) // Old name
            }
        }

        if (nameEquals(BlockNames.TallGrass, BlockNames.TallDryGrass)) {
            yield(baseState to PBRValue.Bool(true))
        }
    }
}


object Emissive : PBRProvider<PBRValue.UInt4> {
    override val defaultValue: PBRValue.UInt4 = PBRValue.UInt4(0u)
    override fun BlockScope.provide(): Sequence<Pair<BlockState, PBRValue.UInt4>> = sequence {
        yield(baseState to PBRValue.UInt4(property.luminance.toUByte()))

        if (nameEquals(BlockNames.Torchflower)) {
            yield(baseState to PBRValue.UInt4(12u))
        }
        if (nameEquals(BlockNames.PitcherPlant)) {
            yield(baseState to PBRValue.UInt4(10u))
        }
    }
}

object IOR : PBRProvider<PBRValue.Unorm8> {
    const val MAXIMUM_IOR = 3.0f

    private fun encodeIOR(ior: Float): PBRValue.Unorm8 {
        return PBRValue.Unorm8((ior / MAXIMUM_IOR).coerceIn(0.0f, 1.0f))
    }

    override val defaultValue: PBRValue.Unorm8 = encodeIOR(1.5f)

    override fun BlockScope.provide(): Sequence<Pair<BlockState, PBRValue.Unorm8>> = sequence {
        // https://pixelandpoly.com/ior.html
        if (nameContains("glass")) {
            yield(baseState to encodeIOR(1.5f))
        }

        if (nameEquals(BlockNames.BlockofDiamond)) {
            yield(baseState to encodeIOR(2.42f))
        }

        if (nameContains(BlockNames.BlockofEmerald)) {
            yield(baseState to encodeIOR(1.58f))
        }
    }
}

object Roughness : PBRProvider<PBRValue.Unorm8> {
    private fun encodeRoughness(roughness: Float): PBRValue.Unorm8 {
        return PBRValue.Unorm8(sqrt(roughness))
    }

    override val defaultValue: PBRValue.Unorm8 = encodeRoughness(0.5f)

    override fun BlockScope.provide(): Sequence<Pair<BlockState, PBRValue.Unorm8>> = sequence {
        if (nameContains("glass")) {
            yield(baseState to encodeRoughness(0.0f))
        }
        if (nameEquals("ice")) {
            yield(baseState to encodeRoughness(0.01f))
        }
    }
}