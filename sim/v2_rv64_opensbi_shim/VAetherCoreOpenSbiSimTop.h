#pragma once

#include "VAetherCoreV2OpenSbiRV64SimTop.h"

// Compile-time compatibility surface only. The qualified shared OpenSBI/Linux
// runner keeps its historical type name while Verilator instantiates the F7 v2
// RV64 top; no second runtime implementation is introduced.
using VAetherCoreOpenSbiSimTop = VAetherCoreV2OpenSbiRV64SimTop;
