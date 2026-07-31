package dev.luna5ama.shadesmith.blockcode

import kotlin.math.sqrt

private data class MaterialPBR(
    val ior: Float,
    val roughness: Float,
    val metalIndex: UByte = 0u,
    val dielectric: Float = 1.0f,
)

private object LabPBRMetal {
    const val IRON: UByte = 1u
    const val GOLD: UByte = 2u
    const val COPPER: UByte = 5u
}

private fun BlockScope.nameContainsAny(vararg substrings: String): Boolean =
    substrings.any { baseState.name.contains(it) }

private fun BlockScope.nameEqualsAny(vararg names: String): Boolean =
    names.any { baseState.name == it }

private fun BlockScope.nameStartsWithAny(vararg prefixes: String): Boolean =
    prefixes.any { baseState.name.startsWith("${it}_") }

private fun BlockScope.materialPBR(): MaterialPBR {
    val name = baseState.name
    return when {
        nameEqualsAny("air", "cave_air", "void_air", "structure_void") ->
            MaterialPBR(1.0f, 1.0f)
        nameEqualsAny(BlockNames.Water, "bubble_column") ->
            MaterialPBR(1.333f, 0.02f)
        nameContains("glass") ->
            MaterialPBR(1.5f, 0.0f)
        nameEqualsAny("ice", "packed_ice", "blue_ice", "frosted_ice") ->
            MaterialPBR(1.31f, 0.02f)
        nameContainsAny("powder_snow", "snow") ->
            MaterialPBR(1.31f, 0.95f)
        nameContains("honey") ->
            MaterialPBR(1.46f, 0.12f)
        nameContains("slime") ->
            MaterialPBR(1.38f, 0.08f)
        nameEqualsAny("sea_lantern", "conduit", "jack_o_lantern") ->
            MaterialPBR(1.5f, 0.28f)
        nameEqualsAny("ender_chest", "crying_obsidian") ->
            MaterialPBR(1.5f, 0.22f)
        nameEquals(BlockNames.BlockofDiamond) ||
            (nameContains("diamond") && !nameContains("ore")) ->
            MaterialPBR(2.42f, 0.1f)
        nameContains("emerald") && !nameContains("ore") ->
            MaterialPBR(1.58f, 0.12f)
        nameContains("amethyst") ->
            MaterialPBR(1.55f, 0.14f)
        nameContains("quartz") && !nameContains("ore") ->
            MaterialPBR(1.54f, 0.28f)
        nameContains("lapis") && !nameContains("ore") ->
            MaterialPBR(1.5f, 0.35f)
        nameContains("raw_") && nameContains("iron") ->
            MaterialPBR(2.2f, 0.38f, LabPBRMetal.IRON, 0.45f)
        nameContains("raw_") && nameContains("gold") ->
            MaterialPBR(2.2f, 0.38f, LabPBRMetal.GOLD, 0.45f)
        nameContains("raw_") && nameContains("copper") ->
            MaterialPBR(2.2f, 0.38f, LabPBRMetal.COPPER, 0.45f)
        nameContains("iron_ore") ->
            MaterialPBR(1.5f, 0.8f, LabPBRMetal.IRON, 0.85f)
        nameContains("gold_ore") ->
            MaterialPBR(1.5f, 0.8f, LabPBRMetal.GOLD, 0.85f)
        nameContains("copper_ore") ->
            MaterialPBR(1.5f, 0.8f, LabPBRMetal.COPPER, 0.85f)
        nameContains("netherite") ->
            MaterialPBR(2.7f, 0.18f, dielectric = 0.0f)
        nameContains("oxidized_copper") && !nameContains("ore") ->
            MaterialPBR(2.1f, 0.45f, LabPBRMetal.COPPER, 0.9f)
        nameContains("weathered_copper") && !nameContains("ore") ->
            MaterialPBR(2.2f, 0.38f, LabPBRMetal.COPPER, 0.6f)
        nameContains("exposed_copper") && !nameContains("ore") ->
            MaterialPBR(2.3f, 0.32f, LabPBRMetal.COPPER, 0.25f)
        nameContains("copper") && !nameContains("ore") ->
            MaterialPBR(2.4f, 0.25f, LabPBRMetal.COPPER, 0.0f)
        nameContains("gold") &&
            !nameContainsAny("ore", "gilded", "golden_dandelion") ->
            MaterialPBR(2.4f, 0.2f, LabPBRMetal.GOLD, 0.0f)
        nameContains("iron") && !nameContains("ore") ->
            MaterialPBR(2.5f, 0.3f, LabPBRMetal.IRON, 0.0f)
        nameContains("oxidized_lightning_rod") ->
            MaterialPBR(2.1f, 0.45f, LabPBRMetal.COPPER, 0.9f)
        nameContains("weathered_lightning_rod") ->
            MaterialPBR(2.2f, 0.38f, LabPBRMetal.COPPER, 0.6f)
        nameContains("exposed_lightning_rod") ->
            MaterialPBR(2.3f, 0.32f, LabPBRMetal.COPPER, 0.25f)
        nameContains("lightning_rod") ->
            MaterialPBR(2.4f, 0.25f, LabPBRMetal.COPPER, 0.0f)
        nameContains("bell") ->
            MaterialPBR(2.4f, 0.25f, dielectric = 0.1f)
        nameContains("rail") ->
            MaterialPBR(2.5f, 0.35f, LabPBRMetal.IRON, 0.45f)
        nameContainsAny("iron_bars", "chain") ->
            MaterialPBR(2.5f, 0.3f, LabPBRMetal.IRON, 0.0f)
        nameContainsAny("anvil", "cauldron", "hopper") ->
            MaterialPBR(2.5f, 0.35f, LabPBRMetal.IRON, 0.1f)
        nameContains("lantern") ->
            MaterialPBR(2.5f, 0.3f, LabPBRMetal.IRON, 0.35f)
        nameContainsAny(
            "leaves", "sapling", "flower", "grass", "fern", "moss", "vine", "roots", "fungus",
            "wart", "coral", "kelp", "seagrass", "cactus", "sugar_cane", "crop", "carrot",
            "potato", "beetroot", "wheat", "bamboo_shoot", "bush", "petals", "dripleaf", "spore_blossom",
            "mushroom", "nylium"
        ) ->
            MaterialPBR(1.45f, 0.9f)
        nameContains("cake") ->
            MaterialPBR(1.45f, 0.9f)
        nameContains("candle") ->
            MaterialPBR(1.45f, 0.45f)
        nameContainsAny("wool", "carpet", "banner", "bed") ->
            MaterialPBR(1.45f, 0.95f)
        nameStartsWithAny(
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry",
            "pale_oak", "bamboo", "crimson", "warped"
        ) || nameContainsAny("planks", "log", "wood", "hyphae", "stem", "mosaic", "bookshelf",
            "barrel", "chest", "sign", "shelf") ->
            MaterialPBR(1.45f, 0.75f)
        nameContains("glazed") ->
            MaterialPBR(1.5f, 0.3f)
        nameContainsAny("polished", "smooth_", "cut_", "chiseled", "tiles") ->
            MaterialPBR(1.5f, 0.45f)
        nameContainsAny("concrete", "terracotta") ->
            MaterialPBR(1.5f, 0.7f)
        nameContainsAny("sand", "gravel", "dirt", "soil", "clay", "mud") ->
            MaterialPBR(1.45f, 0.92f)
        nameContainsAny("fire", "portal", "glowstone", "shroomlight", "froglight") ->
            MaterialPBR(1.5f, 0.2f)
        else -> MaterialPBR(1.5f, 0.8f)
    }
}

object SSS : PBRProvider<PBRValue.UInt4> {
    override val defaultValue: PBRValue.UInt4 = PBRValue.UInt4(0u)
    override fun BlockScope.provide(): Sequence<Pair<BlockState, PBRValue.UInt4>> = sequence {
        val value: UByte? = when {
            property.tags.contains(BlockProperty.Tags.SmallFlower) -> 15u
            property.tags.contains(BlockProperty.Tags.Flower) && !nameEndsWith("_leaves") -> 13u
            nameEndsWith("_sapling") -> 12u
            nameEquals(BlockNames.ShortDryGrass, BlockNames.ShortGrass) -> 14u
            nameEquals(BlockNames.TallGrass, BlockNames.TallDryGrass) -> 12u
            nameEndsWith("_leaves") -> 10u
            nameContainsAny("moss", "vine", "roots", "fungus", "wart", "coral", "kelp", "seagrass",
                "cactus", "sugar_cane", "crop", "carrot", "potato", "beetroot", "wheat", "bamboo_shoot", "bush",
                "petals", "dripleaf", "spore_blossom", "mushroom", "nylium") -> 8u
            nameContains("quartz") && !nameEquals(BlockNames.NetherQuartzOre) -> 2u
            else -> null
        }
        if (value != null) {
            yield(baseState to PBRValue.UInt4(value))
        }

        if (nameEquals(BlockNames.ShortGrass)) {
            yield(BlockState("grass") to PBRValue.UInt4(14u)) // Old name
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

    override fun BlockScope.provide(): Sequence<Pair<BlockState, PBRValue.Unorm8>> = sequenceOf(
        baseState to encodeIOR(materialPBR().ior)
    )
}

object Roughness : PBRProvider<PBRValue.Unorm8> {
    private fun encodeRoughness(roughness: Float): PBRValue.Unorm8 {
        return PBRValue.Unorm8(sqrt(roughness))
    }

    override val defaultValue: PBRValue.Unorm8 = encodeRoughness(0.8f)

    override fun BlockScope.provide(): Sequence<Pair<BlockState, PBRValue.Unorm8>> = sequenceOf(
        baseState to encodeRoughness(materialPBR().roughness)
    )
}

object MetalIndex : PBRProvider<PBRValue.UInt4> {
    override val defaultValue: PBRValue.UInt4 = PBRValue.UInt4(0u)

    override fun BlockScope.provide(): Sequence<Pair<BlockState, PBRValue.UInt4>> = sequenceOf(
        baseState to PBRValue.UInt4(materialPBR().metalIndex)
    )
}

object Dielectric : PBRProvider<PBRValue.Unorm4> {
    override val defaultValue: PBRValue.Unorm4 = PBRValue.Unorm4(1.0f)

    override fun BlockScope.provide(): Sequence<Pair<BlockState, PBRValue.Unorm4>> = sequenceOf(
        baseState to PBRValue.Unorm4(materialPBR().dielectric)
    )
}

object EmissiveMultiplier : PBRProvider<PBRValue.Int4> {
    private fun encodeEmissiveOverride(override: Int): PBRValue.Int4 {
        return PBRValue.Int4((override and 0b1111).toByte())
    }

    override val defaultValue: PBRValue.Int4 = encodeEmissiveOverride(0)

    override fun BlockScope.provide(): Sequence<Pair<BlockState, PBRValue.Int4>> = sequence {
        if (nameEquals(BlockNames.EndRod)) {
            yield(baseState to encodeEmissiveOverride(-2))
        }
        if (nameEquals(BlockNames.LightningRod)) {
            yield(baseState to encodeEmissiveOverride(-4))
        }
    }
}
