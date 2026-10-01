#include <jni.h>
#include <objc/message.h>
#include <objc/runtime.h>
#include <CoreGraphics/CoreGraphics.h>
#include <stdint.h>

/* CanJoinAllSpaces | IgnoresCycle | FullScreenAuxiliary.
   MoveToActiveSpace must not be combined with CanJoinAllSpaces:
   AppKit raises in _validateCollectionBehavior: and aborts the process. */
#define PMCL_COLLECTION ((unsigned long)((1UL << 0) | (1UL << 6) | (1UL << 8)))

static void pin_window(id window) {
    if (window == 0) return;
    @try {
        ((void (*)(id, SEL, unsigned long))objc_msgSend)(
            window, sel_registerName("setCollectionBehavior:"), PMCL_COLLECTION);
        long level = CGWindowLevelForKey(kCGMaximumWindowLevelKey);
        ((void (*)(id, SEL, long))objc_msgSend)(
            window, sel_registerName("setLevel:"), level);
        ((void (*)(id, SEL, BOOL))objc_msgSend)(
            window, sel_registerName("setHidesOnDeactivate:"), (BOOL)0);
        ((void (*)(id, SEL))objc_msgSend)(window, sel_registerName("orderFrontRegardless"));
    } @catch (id ex) {
        (void)ex;
    }
}

JNIEXPORT void JNICALL
Java_com_pmcl_ui_MacOverlay_00024Natives_pin(JNIEnv *env, jclass cls, jlong windowPtr) {
    (void)env;
    (void)cls;
    pin_window((id)(intptr_t)windowPtr);
}
