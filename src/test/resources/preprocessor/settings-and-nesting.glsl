#version 460 core
#define SETTING_QUALITY 2 //[0 1 2]
//#define SETTING_FOG

#if SETTING_QUALITY == 0
const int quality = 0;
#elif SETTING_QUALITY == 1
const int quality = 1;
#else
const int quality = 2;
#endif

void shade() {
    #ifdef SETTING_FOG
    float fog = 1.0;
        #if defined(SETTING_DETAIL) && SETTING_DETAIL > 1
        fog *= 2.0;
        #endif
    #else
    float fog = 0.0;
    #endif
}
