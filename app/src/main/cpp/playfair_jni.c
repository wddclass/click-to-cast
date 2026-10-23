#include <jni.h>
#include <string.h>
#include "playfair.h"

JNIEXPORT jbyteArray JNICALL
Java_com_wddclass_clicktocast_airplay_PlayfairNative_nativeDecrypt(
        JNIEnv *env, jclass clazz, jbyteArray message, jbyteArray cipher) {
    (void) clazz;
    if (message == NULL || cipher == NULL) return NULL;
    jsize messageLen = (*env)->GetArrayLength(env, message);
    jsize cipherLen = (*env)->GetArrayLength(env, cipher);
    if (messageLen < 164 || cipherLen < 72) return NULL;

    /* Copy onto aligned stack buffers. playfair reads the key message as bytes
     * and the ekey from offset 16 and 56. */
    /* 8-byte alignment: omg_hax casts nearby buffers to uint32_t on ARM. */
    unsigned char keymsg[164] __attribute__((aligned(8)));
    unsigned char ekey[72] __attribute__((aligned(8)));
    unsigned char out[16] __attribute__((aligned(8)));
    memset(keymsg, 0, sizeof(keymsg));
    memset(ekey, 0, sizeof(ekey));
    (*env)->GetByteArrayRegion(env, message, 0, 164, (jbyte *) keymsg);
    (*env)->GetByteArrayRegion(env, cipher, 0, 72, (jbyte *) ekey);
    playfair_decrypt(keymsg, ekey, out);

    jbyteArray result = (*env)->NewByteArray(env, 16);
    if (result == NULL) return NULL;
    (*env)->SetByteArrayRegion(env, result, 0, 16, (const jbyte *) out);
    return result;
}
