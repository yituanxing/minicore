# L32 software inputs

This directory owns the immutable software inputs for the RV32 OpenSBI + Linux bring-up line.

`manifest.env` is authoritative for the checkpoint. Do not silently advance Linux or OpenSBI while debugging AetherCore. A version change is a separate compatibility operation and must not replace the frozen workload.
