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
4. compile with `glslang --target-env opengl --target-env spirv1.3`, retaining OpenGL semantics while supporting
   subgroup operations;
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

Standard stage suffixes determine standalone compiler stages. A suffixless `.glsl` Voxy hook declaring
`voxy_emitFragment` is a host-integration fragment: without the host-provided parameter type, `#version`, and `main`, it
cannot truthfully be optimized as an independent stage. Shadesmith preserves its include-expanded source, uses a
conservative source-level lifecycle union, and records the intentional boundary in `boundaries.tsv`; other suffixless
contracts fail instead of guessing. Clang-only branch materialization normalizes token paste before an opening
delimiter, while the source-visible macro body remains exact. Only `SETTING_` conditionals are varied; host integration
guards keep the current source macro environment, and an enumerated option domain does not create an impossible
fallthrough variant.

Original stage, uniform, resource-block, and dependent struct declarations are restored, including legal uniform
initializers, when SPIR-V optimization removes them with dead code. Anonymous resource blocks are restored as anonymous
blocks and temporary SPIRV-Cross instance prefixes are removed. Generated bindings and locations exist only in isolated
compiler copies. Temporary bindings use OpenGL's independent sampler, image, atomic-counter, uniform-block, and
storage-block namespaces. Samplers beyond glslang's portable 80-unit compiler limit reuse compiler-only bindings;
linked ABI verification uses the restored stages together so compiler-only resources or
declaration order cannot redefine the emitted interface. Workgroup and stage layouts remain strict ABI checks while
constant built-in references may fold.

## Diagnostics

Round-trip artifacts are retained beside the output directory in `.<output-name>.spirv`. Failures report the shader
source, stage, pipeline phase, external tool, command logs, and artifact directory. Shader output is cleared only after
all stages and variants have completed successfully, so a compile or tool failure cannot leave a partially transformed
shader set. Standalone roots and their protected setting variants are processed with bounded parallelism after
deterministic source ordering. If multiple roots fail, all submitted roots and variants finish, each keeps its own
artifacts, and `failures.tsv` records every failure in source
order with its stage, phase, command, and artifact path.
