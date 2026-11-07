#!/usr/bin/env python3
"""No-guest negative checks for strict/fast G3 functional-state comparison."""

from product_gsim_fast_ab import (DETAIL_FIELDS, TERMINAL_FIELDS,
                                  bridge_getter_budget, compare_functional)


def reject(fn, needle: str) -> None:
    try:
        fn()
    except ValueError as error:
        assert needle in str(error), (needle, str(error))
    else:
        raise AssertionError(f"accepted unsafe {needle}")


def main() -> None:
    terminal = dict.fromkeys(TERMINAL_FIELDS, "0")
    terminal.update(cycles="30000000", commits="1000", **{"uart-bytes": "96", "banner": "1"})
    detail = dict.fromkeys(DETAIL_FIELDS, "0")
    detail.update(guest_cycles="29999996", retires="1000", host_bridge_calls="4000",
                  host_imem_calls="2000", opensbi_banner="1")
    strict_detail = dict(detail, trace_mode="strict-same-cycle",
                         commit_digest="0x12345", wire_digest="0x67890")
    fast_detail = dict(detail, trace_mode="fast-g4-unqualified",
                       commit_digest="OMITTED", wire_digest="OMITTED")
    compare_functional(terminal, terminal, strict_detail, fast_detail, 30000000)
    assert bridge_getter_budget(strict_detail, 30000000) == (4000, 56000, 16000, 16000)
    reject(lambda: bridge_getter_budget({**strict_detail, "host_imem_calls": "4001"}, 30000000),
           "invalid Product host callback/getter budget counters")
    reject(lambda: compare_functional(terminal, terminal, strict_detail,
                                      {**fast_detail, "trace_mode": "strict-same-cycle"}, 30000000),
           "fast-g4-unqualified")
    reject(lambda: compare_functional(terminal, terminal, strict_detail,
                                      {**fast_detail, "wire_digest": "0xdead"}, 30000000),
           "omit wire_digest")
    reject(lambda: compare_functional(terminal, {**terminal, "ram-fingerprint": "42"},
                                      strict_detail, fast_detail, 30000000),
           "ram-fingerprint differs")
    reject(lambda: compare_functional(terminal, terminal, strict_detail,
                                      {**fast_detail, "interrupts": "42"}, 30000000),
           "interrupts differs")
    reject(lambda: compare_functional(terminal, terminal, strict_detail,
                                      {**fast_detail, "host_bridge_calls": "42"}, 30000000),
           "host_bridge_calls differs")
    reject(lambda: compare_functional(terminal, terminal,
                                      {**strict_detail, "trace_mode": "fast-g4-unqualified"},
                                      fast_detail, 30000000), "strict-same-cycle")
    print("PRODUCT_GSIM_FAST_AB_SELFTEST_PASS positive=2 negatives=7 guest_cycles=0")


if __name__ == "__main__":
    main()
