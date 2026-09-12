#include <jni.h>
#include <cstdio>

extern "C"
JNIEXPORT void JNICALL
Java_com_duc1607_q25led_MainActivityKt_setLedRGB(JNIEnv *env, jclass clazz, jint r, jint g,
                                                 jint b) {
    FILE *f = fopen("/sys/class/leds/red/brightness", "wt");
    if (f) {
        fprintf(f, "%u\n", r);
        fclose(f);
        f = fopen("/sys/class/leds/green/brightness", "wt");
        if (f) {
            fprintf(f, "%u\n", g);
            fclose(f);
        }
        f = fopen("/sys/class/leds/blue/brightness", "wt");
        if (f) {
            fprintf(f, "%u\n", b);
            fclose(f);
        }
    }
}