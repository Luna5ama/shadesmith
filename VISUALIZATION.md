# Texture Usage Visualization

ShadeSmith now includes comprehensive visualization capabilities for texture allocation and usage patterns.

## Features

### 1. ASCII Timeline Visualization
Shows the lifetime of textures across rendering passes in a text-based format.

- **Transient Textures**: Displayed with `███` blocks showing when they are active
- **History Textures**: Displayed with `RRR` for read passes and `WWW` for write passes
- Passes are shown as columns with abbreviated names
- Textures are grouped by type (Transient/History) and sorted by first usage

Example output:
```
================================================================================
TEXTURE LIFETIME VISUALIZATION
================================================================================

                  │ beg com def end 
──────────────────┼────────────────
TRANSIENT TEXTURES:
transient_temp1   │ ███ ███         
transient_temp2   │     ███ ███     

HISTORY TEXTURES:
history_buffer1   │ RRR RRR     WWW 
================================================================================
```

### 2. ASCII Atlas Packing Visualization
Shows how textures are packed into texture atlases for each format.

- Displays a grid layout showing tile positions
- Each tile shows the primary texture name
- Lists all textures assigned to each tile
- Shows dimensions (e.g., 2x3 for 6 tiles)

Example output:
```
================================================================================
TEXTURE ATLAS PACKING VISUALIZATION
================================================================================

Format: RGBA16F (4 tiles)
────────────────────────────────────────────────────────────
┌────────┐ ┌────────┐ 
│trans_a │ │trans_b │ 
└────────┘ └────────┘ 
┌────────┐ ┌────────┐ 
│history_c│ │history_d│ 
└────────┘ └────────┘ 

Textures:
  Tile 0: trans_a
  Tile 1: trans_b
  Tile 2: history_c
  Tile 3: history_d

================================================================================
```

### 3. HTML/SVG Interactive Visualizations
Generates HTML files with interactive charts using the lets-plot library.

#### Timeline Chart (`texture_timeline.html`)
- Interactive timeline showing texture lifetimes
- Color-coded by state (Active/Read/Write)
- Hover for details
- Zoomable and pannable

#### Atlas Packing Charts (`atlas_packing_{FORMAT}.html`)
- One chart per texture format
- Shows spatial layout of textures in the atlas
- Color-coded by texture
- Labels show texture names

## Usage

The visualizations are automatically generated when running the texture resolution phase:

```kotlin
resolveTextures(inputFiles)
```

Output locations:
- **Console**: ASCII visualizations are printed to stdout
- **HTML files**: Saved to the output directory (same as shader output)
  - `texture_timeline.html` - Timeline visualization
  - `atlas_packing_{FORMAT}.html` - One per texture format

## Implementation Details

### Data Structures

#### LifeTimeRange
Tracks when textures are active:
- **Transient**: Simple range (first use to last use)
- **History**: Split range (reads before writes, writes at end)

#### AllocationInfo
Tracks texture-to-tile assignments:
- `tileID`: Map of texture name to tile ID
- `tileCount`: Total number of tiles needed

### Tile Layout Algorithm
Uses a predefined grid pattern optimized for square/rectangular atlases:
- 1 tile: 1x1
- 2-4 tiles: 2x2
- 5-9 tiles: 3x3
- 10-16 tiles: 4x4
- 17+ tiles: Custom layouts

### Visualization Library
Uses [lets-plot](https://github.com/JetBrains/lets-plot-kotlin) for HTML/SVG generation:
- Declarative grammar of graphics API
- High-quality SVG output
- Interactive features
- Easily customizable

## Benefits

1. **Debug Texture Lifetimes**: Quickly identify when textures are read/written
2. **Optimize Memory**: See which textures can share atlas space
3. **Verify Packing**: Ensure atlas packing is efficient
4. **Documentation**: Auto-generate visual documentation of shader pipeline
5. **Performance Analysis**: Identify unnecessary texture allocations

## Example Integration

The visualization system is integrated into `TextureResolver.kt`:

```kotlin
// After calculating lifetimes and allocations...

// Generate ASCII visualizations
println(TextureVisualization.generateASCIITimeline(lifeTime, passCount, passNames))
println(TextureVisualization.generateASCIIAtlasPacking(slots, config))

// Generate HTML visualizations
TextureVisualization.generateHTMLVisualizations(lifeTime, slots, passNames, outputPath)
```

## Future Enhancements

Potential additions:
- Memory usage estimates per format
- Texture access frequency heatmaps
- Dependency graphs between passes
- Animation showing texture state changes
- Export to other formats (PNG, SVG standalone)
- Statistical summaries (avg lifetime, fragmentation, etc.)

