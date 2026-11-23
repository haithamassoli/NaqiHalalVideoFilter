// Objective-C surface visible to the Swift app module.
//
// One entry, and it is here rather than in Swift because ORT's arena controls
// exist only in the C/C++ API — see `NaqiOrtArena.h` for why that is and what
// it costs.
#import "NaqiOrtArena.h"
