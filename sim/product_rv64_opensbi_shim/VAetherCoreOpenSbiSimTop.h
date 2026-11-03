#pragma once

#include "VAetherCoreProductOpenSbiRV64SimTop.h"

// Reuse the historical OpenSBI host runtime exactly as-is. The compatible
// Product top owns every actual execution, memory and retire interface; this
// shim provides only the header/type alias expected by the shared C++ source.
// The G3 gate requires the real OpenSBI banner and S-mode UART milestone, so
// a CPU that merely elaborates, or retires only the BootROM, cannot pass.
using VAetherCoreOpenSbiSimTop = VAetherCoreProductOpenSbiRV64SimTop;
