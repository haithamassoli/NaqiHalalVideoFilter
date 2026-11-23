#import "NaqiOrtArena.h"

#import <objc/message.h>
#import <objc/runtime.h>
#import <os/log.h>
#import <onnxruntime/onnxruntime_cxx_api.h>

// Why `objc_msgSend` and not a category on `ORTSessionOptions`:
//
// The accessor is declared in the package's `objectivec/ort_session_internal.h`,
// which is not in its public `include/`. Re-declaring it as a category needs the
// real `@interface`, and that is only reachable through the
// `OnnxRuntimeBindings` module — which an Objective-C++ file cannot `@import`
// because the target builds with `-fno-cxx-modules`. Turning C++ modules on for
// one file is a much larger change than this.
//
// So the method is called the way the runtime actually calls it. Casting
// `objc_msgSend` to the callee's signature is Apple's documented form for
// return types that are not `id`; a C++ reference is pointer-sized and comes
// back in the return register exactly like one, on arm64 and x86_64 alike.
//
// The safety of this rests on `respondsToSelector:` below, not on the cast: if
// ORT ever drops the accessor, this returns NO and the session keeps its arena.
// It cannot call into nothing.
namespace {
using CXXAccessor = Ort::SessionOptions &(*)(id, SEL);
}

BOOL NaqiOrtDisableArena(ORTSessionOptions *options) {
    if (options == nil) { return NO; }

    // `id`, because `ORTSessionOptions` is only forward-declared here — see the
    // note above. The parameter stays typed so the Swift call site is checked.
    id opts = options;
    SEL accessor = NSSelectorFromString(@"CXXAPIOrtSessionOptions");
    if (![opts respondsToSelector:accessor]) {
        os_log_error(OS_LOG_DEFAULT,
                     "[naqi:ml] ORTSessionOptions no longer exposes CXXAPIOrtSessionOptions; "
                     "the CPU arena stays enabled and htdemucs will exceed the memory budget");
        return NO;
    }

    try {
        // `Ort::SessionOptions` wraps the same `OrtSessionOptions*` the C API
        // takes, so these are the two C API calls the Objective-C layer omits,
        // not a parallel mechanism.
        Ort::SessionOptions &cxx = reinterpret_cast<CXXAccessor>(objc_msgSend)(opts, accessor);
        cxx.DisableCpuMemArena();
        cxx.DisableMemPattern();
    } catch (const Ort::Exception &e) {
        // ORT throws through the C++ wrapper. An exception must not cross back
        // into Swift.
        os_log_error(OS_LOG_DEFAULT, "[naqi:ml] disabling the ORT arena failed: %{public}s", e.what());
        return NO;
    } catch (...) {
        os_log_error(OS_LOG_DEFAULT, "[naqi:ml] disabling the ORT arena failed");
        return NO;
    }
    return YES;
}
