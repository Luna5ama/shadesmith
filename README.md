# Shadesmith

Shadesmith expands an Iris shader pack, optimizes supported GLSL through OpenGL SPIR-V, emits the optimized
SPIRV-Cross GLSL, and derives Textile texture lifetimes from the optimized program semantics.

## Requirements

Java 21 and these executables must be available on `PATH`:

- `clang`, used for include expansion and compiler-copy preprocessing;
- `glslang`, used in OpenGL mode for SPIR-V generation and final-GLSL validation;
- `spirv-opt`, used for the configured SPIRV-Tools optimization sequence;
- `spirv-cross`, used to decompile optimized modules to desktop GLSL 4.60.

Run the fat JAR with an input shader directory and an output shader directory:

```text
java -Xmx6g -jar shadesmith.jar <input-shaders> <output-shaders>
```

The bounded cold-path scheduler is validated with a 6 GiB Java heap. Keep that limit when processing the complete
Alpha Piscium corpus so the JVM collects completed root plans before the total process tree exceeds 8 GiB.

## Shader pipeline

For each standalone shader root, Shadesmith:

1. expands includes while retaining lossless source slices for Iris settings and directive contracts;
2. infers scalar setting domains and lowers safe function-body conditionals to specialization-constant-controlled
   compiler-copy control flow;
3. removes host-only Iris contracts from the compiler copy and represents supported setting-dependent workgroup sizes
   through `local_size_*_id` when the cached tool capability probe permits it;
4. creates only the bounded structural modules required by incompatible resource, interface, capability, layout, or
   function ABI signatures;
5. compiles each module with `glslang --target-env opengl --target-env spirv1.3`;
6. runs `spirv-opt` with `--eliminate-dead-branches`, `--merge-return`,
   `--inline-entry-points-exhaustive`, `--scalar-replacement=0`, `--ssa-rewrite`,
   `--simplify-instructions`, `--eliminate-dead-inserts`, `--eliminate-dead-functions`,
   `--eliminate-dead-code-aggressive`, and `--merge-blocks`, in that order;
7. runs `spirv-cross --no-es --version 460 --glsl-force-flattened-io-blocks`
   `--combined-samplers-inherit-bindings --remove-unused-variables`;
8. removes SPIRV-Cross specialization declarations, installs Iris setting bridges, restores exact directive and host
   contract slices at stable anchors, and recompiles the final GLSL in OpenGL mode;
9. unions optimized lifecycle access from the ordinary setting control flow and every required structural signature.

Ordinary settings never create branch variants. Presence toggles become `#ifdef`-driven boolean bridges; numeric
options remain references to their original Iris macros, so Iris preprocessing and the final OpenGL compiler can fold
the optimized control flow. The emitted shader is the optimized SPIRV-Cross result, not the compiler copy or the
include-expanded input.

Preprocessor regions that cannot coexist in one legal module are structural. Settings are grouped only when they share
structural dependencies, rows are deduplicated by ABI/capability signature, and each root is limited to 32 structural
modules. If restored structural modules do not share one optimized semantic body, or an exact contract anchor cannot be
recovered, processing fails with retained diagnostics and leaves previous output intact. Standalone shaders must pass
the round trip; suffixless host fragments without a `#version`/`main` contract are preserved explicitly.

`workGroups`, `workGroupsRender`, buffer and shadow host constants, buffer-format comment directives, and
`DRAWBUFFERS`/`RENDERTARGETS` comments are source contracts: their original bytes and ordering are restored after the
round trip. Local-size directives are likewise restored from the original source rather than accepted from
SPIRV-Cross. The compiler-copy surrogate and specialization declarations never appear in final output.

Logical Textile accesses use temporary, per-texture resource markers in compiler copies so dead functions and dead
branches disappear before lifecycle analysis. Those markers, compiler-only bindings, generated locations, and
SPIRV-Cross constant-ID macros are stripped before output. Final validation checks the restored OpenGL ABI for every
structural signature.

## Cache, batching, and diagnostics

Round-trip artifacts are retained beside the output directory in `.<output-name>.spirv`. Every failure keeps the exact
expanded input, command, stdout/stderr, phase, stack trace, and artifact path; `failures.tsv` reports failures in stable
source order. `boundaries.tsv` records preserved host/structural boundaries, `outputs.tsv` records final hashes and
lifecycle data, and `performance.tsv` records module, cache, batching, process, concurrency, and elapsed-time metrics.

Successful roots are published atomically to a content-addressed cache only after lifecycle resolution and every final
output has succeeded. Cache identity binds the complete include-expanded source, stage, configuration, packaged
Shadesmith implementation, hashes of the resolved external tools, and their full argument contracts. Compiler-copy and
structural planning are deterministic products of that identity; a verified hit therefore bypasses planning as well as
all external tools. Missing, incomplete, corrupt, or mismatched entries are recomputed, and failed runs publish nothing.

The cold path uses at most 32 external processes concurrently. Two rolling 16-root planning batches feed at most 16
executing roots, and Clang combines up to twenty independent translation units per process; a failed batch is retried
per input so diagnostics still identify the exact source. Repeated runs reuse verified final GLSL and
lifecycle/signature metadata without starting Clang, glslang, spirv-opt, or spirv-cross.
