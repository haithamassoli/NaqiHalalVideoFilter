// ORT's arena controls, which its Objective-C wrapper does not expose.
//
// `DisableCpuMemArena` and `DisableMemPattern` exist only as C API *functions*
// (`onnxruntime_c_api.h`), never as session config keys — the full list in
// `onnxruntime_session_options_config_keys.h` has no arena entry — so
// `-addConfigEntryWithKey:value:` can never reach them, however it is spelled.
//
// This matters because htdemucs peaked at 1721-1881 MB against the PRD's
// 1536 MB budget, and the two cheaper levers are both ruled out by
// measurement: eviction is retention, not working set
// (`AudioPipeline` already does it), and thread count does not move the
// peak at all (`BenchTests.demucsThreadSweep` — 1838 MB at the *minimum* one
// thread, spread ~3.5 % and non-monotonic). Android needed exactly these two
// flags on this same graph; without them lmkd killed it at 5.6 GB RSS, and the
// iOS equivalent is a jetsam kill with no warning.

#import <Foundation/Foundation.h>

@class ORTSessionOptions;

NS_ASSUME_NONNULL_BEGIN

/// Turns off ORT's CPU allocation arena and its memory-pattern planner.
///
/// Returns NO if the wrapper's private accessor has gone — see the
/// implementation for why that is a runtime-safe failure. Callers should log
/// and continue: the session is still valid, it just keeps the arena.
///
/// `FOUNDATION_EXTERN`, not a bare declaration: the implementation is
/// Objective-**C++**, so without `extern "C"` the definition is C++-mangled
/// while Swift's bridging-header import looks for the plain C symbol, and the
/// only sign of it is `Undefined symbols: _NaqiOrtDisableArena` at link time
/// even though the .o compiled perfectly.
FOUNDATION_EXTERN BOOL NaqiOrtDisableArena(ORTSessionOptions *options);

NS_ASSUME_NONNULL_END
