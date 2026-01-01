# Quick Reference: Texture Visualization

## Overview
ShadeSmith automatically generates visualizations of texture usage patterns when processing shaders.

## Features

### 1. ASCII Timeline
Shows texture lifetimes across rendering passes in the console.

**Example:**
```
                      │ beg com def 
──────────────────────┼─────────────
transient_temp        │ ███ ███     
history_buffer        │ RRR     WWW 
```

Legend:
- `███` = Texture is active
- `RRR` = Read operations
- `WWW` = Write operations

### 2. ASCII Atlas Packing
Shows how textures are packed into atlas tiles.

**Example:**
```
Format: RGBA16F (2 tiles)
┌────────┐ ┌────────┐ 
│texture1│ │texture2│ 
└────────┘ └────────┘ 
```

### 3. HTML Charts
Interactive charts saved as HTML files:
- `texture_timeline.html` - Timeline view
- `atlas_packing_{FORMAT}.html` - Atlas layout per format

## Usage

### Viewing Visualizations

1. **Build your shader pack:**
   ```bash
   ./gradlew build
   ```

2. **Console Output:**
   - ASCII visualizations appear in build output
   - Scroll up to see timeline and atlas packing

3. **HTML Files:**
   - Location: Output directory (same as processed shaders)
   - Open in any web browser
   - Interactive: hover, zoom, pan

### Interpreting Results

#### Timeline
- **Overlapping textures**: Can share atlas space if same format
- **Gaps**: Potential for memory reuse
- **Long lifetimes**: Consider if texture really needs to persist

#### Atlas Packing
- **Multiple textures per tile**: Good - memory is being reused
- **One texture per tile**: Review if textures can overlap
- **Many tiles**: Consider if some textures can be combined

## Common Patterns

### Transient Textures
```
transient_temp │ ███ ███     
```
- Used temporarily within a pass sequence
- Memory can be reused after last use

### History Textures
```
history_accum │ RRR     WWW 
```
- Read from previous frame, written for next frame
- Spans multiple frames

### Memory Reuse
```
transient_a   │ ███         
transient_b   │     ███     
transient_c   │         ███ 
```
All three can share the same atlas tile!

## Optimization Tips

1. **Reduce Lifetime**
   - Move texture reads/writes closer together
   - Combine passes that use the same textures

2. **Reuse Atlas Space**
   - Group textures with non-overlapping lifetimes
   - Use same format for similar temporary textures

3. **Minimize History Textures**
   - Only persist data that's truly needed across frames
   - Consider computing on-demand vs. storing

## Troubleshooting

### No Visualizations Generated
- Check console output for errors
- Verify `shadesmith.json` exists with texture formats
- Ensure shader files follow naming convention

### Missing HTML Files
- Check for error message in console
- Verify lets-plot dependency is installed
- Check output directory permissions

### Empty or Incorrect Visualizations
- Verify textures are named correctly (`transient_*` or `history_*`)
- Check that texture operations use correct suffixes (`_sample`, `_store`, etc.)
- Review regex patterns if using custom naming

## Advanced Usage

### Programmatic Access
```kotlin
// In your code
import dev.luna5ama.shadesmith.TextureVisualization

// Generate ASCII timeline
val timeline = TextureVisualization.generateASCIITimeline(
    lifeTime, 
    passCount, 
    passNames
)

// Generate HTML visualizations
TextureVisualization.generateHTMLVisualizations(
    lifeTime,
    slots,
    passNames,
    outputPath
)
```

### Customization
Modify `TextureVisualization.kt` to:
- Change color schemes
- Adjust chart dimensions
- Add custom annotations
- Export to different formats

## Reference

### Texture Naming Convention
- `transient_*` - Temporary texture (single frame)
- `history_*` - Persistent texture (cross-frame)

### Texture Operations (auto-detected)
- `*_sample()` - Texture sampling (read)
- `*_fetch()` - Texel fetch (read)
- `*_gather()` - Texture gather (read)
- `*_load()` - Image load (read)
- `*_store()` - Image store (write)
- `*_atomic*()` - Atomic operations (read-modify-write)

### Output Files
- Console: Standard output during build
- HTML: `{output_dir}/texture_timeline.html`
- HTML: `{output_dir}/atlas_packing_{FORMAT}.html`

## FAQ

**Q: Why are some textures not shown?**
A: Only textures defined in `shadesmith.json` and actually used in shaders are visualized.

**Q: Can I disable visualizations?**
A: Comment out the visualization calls in `TextureResolver.kt`.

**Q: Can I export as PNG/SVG?**
A: Currently HTML/SVG only. Open HTML in browser and use built-in save/export.

**Q: How do I share visualizations?**
A: Share the HTML files - they're self-contained and work offline.

**Q: Can I visualize other metrics?**
A: Extend `TextureVisualization.kt` to add custom charts.

## Learn More

- `VISUALIZATION.md` - Detailed documentation
- `VisualizationDemo.kt` - Working example
- `TextureVisualization.kt` - Source code
- `LifeTimeRange.kt` - Data structures

