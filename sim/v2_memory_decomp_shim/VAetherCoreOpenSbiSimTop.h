#pragma once

#include "VAetherCoreV2OpenSbiRV64SimTop.h"

// Measurement-only compatibility alias. The production OpenSBI/Linux runner
// keeps its historical type while this build elaborates the stacked memory
// decomposition top under the same qualified module name.
using VAetherCoreOpenSbiSimTop = VAetherCoreV2OpenSbiRV64SimTop;

#ifdef AETHERCORE_V2_PERF
// Preserve the frozen P8 host observation first, then add the independent
// memory-decomposition observer. Neither wrapper changes architectural inputs.
#include "../v2_rv64_opensbi_shim/v2_perf_host_hook.h"
#include "v2_memory_decomp_host_hook.h"
#endif
