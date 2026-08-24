#pragma once

#include "VAetherCoreV2OpenSbiRV64SimTop.h"

// Compile-time compatibility surface only. The qualified shared OpenSBI/Linux
// runner keeps its historical type name while Verilator instantiates the F7 v2
// RV64 top; no second runtime implementation is introduced.
using VAetherCoreOpenSbiSimTop = VAetherCoreV2OpenSbiRV64SimTop;

#ifdef AETHERCORE_V2_PERF
// P8-only host observation hook. It wraps the already-qualified step() call
// after including the shared runtime, so cycle and host-memory ordering remain
// unchanged outside the performance build.
#include "v2_perf_host_hook.h"
#include "v2_branch_perf_host_hook.h"
#endif
