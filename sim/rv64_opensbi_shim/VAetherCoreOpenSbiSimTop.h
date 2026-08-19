#pragma once

#include "VAetherCoreOpenSbiRV64SimTop.h"

// Compile-time compatibility surface only. The shared OpenSBI/Linux host
// runner keeps its historical type name while Verilator instantiates the real
// RV64 top; no second runtime implementation is introduced.
using VAetherCoreOpenSbiSimTop = VAetherCoreOpenSbiRV64SimTop;
