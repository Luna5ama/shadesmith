#version 460 core
/*const*/
#define PASS_INDEX 1
#define CLEAR_IMAGE uimg_main
#define CLEAR(index) imageStore(CLEAR_IMAGE, index, vec4(0.0))
#if PASS_INDEX == 1
#define CLEAR_OFFSET ivec2(0)
#else
#define CLEAR_OFFSET ivec2(1)
#endif
/*const*/

void main() {
    CLEAR(CLEAR_OFFSET);
}
