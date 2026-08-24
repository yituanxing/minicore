#pragma once

#include "VAetherCoreV2OpenSbiRV64SimTop.h"

// Compile-time compatibility surface only. The qualified shared OpenSBI/Linux
// runner keeps its historical type name while Verilator instantiates the F7 v2
// RV64 top; no second runtime implementation is introduced.
using VAetherCoreOpenSbiSimTop = VAetherCoreV2OpenSbiRV64SimTop;

#ifdef AETHERCORE_V2_PERF
// P8-only host observation hook. ROB8 adds a narrow overlay that appends the
// four new occupancy buckets after the already-qualified base hook snapshots;
// stepping and host-memory ordering remain owned by the base hook.
#include "v2_rob8_perf_host_hook.h"
#endif
