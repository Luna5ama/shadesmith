# Shadesmith

Shadesmith expands an Iris shader pack, optimizes supported GLSL through OpenGL SPIR-V, and derives Textile texture
lifetimes from the optimized program semantics.

## Requirements

Java 21 and these executables must be available on `PATH`:

- `clang`, used for include expansion and explicit preprocessor-branch materialization;
- `glslang`, used in OpenGL mode for the initial and restored-source compilations;
- `spirv-opt`, used for the requested SPIRV-Tools optimization sequence;
- `spirv-cross`, used to decompile optimized modules to desktop GLSL 4.60.

Run the fat JAR with an input shader directory and an output shader directory:

```text
java -jar shadesmith.jar <input-shaders> <output-shaders>
```

## Shader pipeline

For every discovered stage, Shadesmith performs these steps before replacing any emitted shader output:

1. expand `#include` directives while retaining Iris settings and macro contracts;
2. materialize every protected conditional branch needed for conservative setting coverage;
3. patch only the compiler copy for OpenGL SPIR-V compatibility;
4. compile with `glslang --target-env opengl`;
5. run `spirv-opt` with `--eliminate-dead-branches`, `--merge-return`,
   `--inline-entry-points-exhaustive`, `--scalar-replacement=0`, `--ssa-rewrite`,
   `--simplify-instructions`, `--eliminate-dead-inserts`, `--eliminate-dead-functions`,
   `--eliminate-dead-code-aggressive`, and `--merge-blocks`, in that order;
6. run `spirv-cross --no-es --version 460 --glsl-force-flattened-io-blocks`
   `--combined-samplers-inherit-bindings --remove-unused-variables`;
7. restore Iris/OpenGL source contracts and compile the restored result again in OpenGL mode;
8. union optimized read/write facts from all retained setting variants for Textile allocation, then emit shaders and
   texture properties.

A shader without protected macro structure emits the optimized round-trip GLSL. When configurable preprocessing must
remain source-visible, the include-expanded source is emitted with those directives intact. Its lifecycle facts still
come from every optimized materialized variant. Logical Textile accesses use temporary, per-texture resource markers in
the compiler copies so dead functions and dead branches disappear before lifetime analysis; those markers are never
written to the emitted shader.

## Diagnostics

Round-trip artifacts are retained beside the output directory in `.<output-name>.spirv`. Failures report the shader
source, stage, pipeline phase, external tool, command logs, and artifact directory. Shader output is cleared only after
all stages and variants have completed successfully, so a compile or tool failure cannot leave a partially transformed
shader set.
