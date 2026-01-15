#include <jni.h>
#include <string>
#include <algorithm>

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_learningkeyboard_MainActivity_processTextInCpp(
        JNIEnv* env,
        jobject /* this */,
        jstring input_text) {
    
    // 1. Convert JNI string to C++ std::string
    const char *nativeString = env->GetStringUTFChars(input_text, nullptr);
    std::string text(nativeString);
    env->ReleaseStringUTFChars(input_text, nativeString);

    // 2. This is where your LLM or Logic goes.
    // For now, let's do a simple "transformation" to prove it works.
    // We will uppercase the text and add a tag.
    std::transform(text.begin(), text.end(), text.begin(), ::toupper);
    std::string result = "[LLM: " + text + "]";

    // 3. Convert back to JNI string
    return env->NewStringUTF(result.c_str());
}