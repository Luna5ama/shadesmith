#ifndef INCLUDE_macro_identifiers_glsl
#define INCLUDE_macro_identifiers_glsl a

#define JOIN_IMPL(a, b) a ## b
#define JOIN(a, b) JOIN_IMPL(a, b)
#define usam_main colortex0
#define uimg_main colorimg0
#define SAMPLE_IMAGE(name, uv) texture(name, uv)
#define STORAGE_QUALIFIER buffer
#undef TEMPORARY_ALIAS

#endif
