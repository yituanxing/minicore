#!/usr/bin/env python3
"""Fast synthetic positive/negative checks for the Product G3 state contract."""

from product_gsim_compare import canonical, require_strict_trace, summary


class FakeLog:
    def __init__(self, body: str):
        self.body = body

    def read_text(self, **kwargs):
        return self.body

    def __str__(self):
        return "fixture"


COMMON = dict(
    cycles="30000000", commits="1000", compressed="37", **{
        "opensbi-compressed": "12", "linux-compressed": "25",
        "uart-bytes": "400", "uart-fingerprint": "0xabcdef",
        "banner": "1", "ram-fingerprint": "0x12345678",
        "recent-pcs": "0x80000100,0x80000104",
        "last-exception-pc": "0x800000c0", "last-exception-cause": "0x2",
        "last-exception-value": "0x0",
    },
    exceptions="3", interrupts="2", stip="1", seip="1",
    mtime="0x45", mtimecmp="0x60",
)


def expect_failure(fn, fragment: str):
    try:
        fn()
    except ValueError as error:
        assert fragment in str(error), (fragment, str(error))
    else:
        raise AssertionError(f"Expected fail-closed {fragment}")


def main():
    strict = dict(trace_mode="strict-same-cycle", commit_digest="0xfabc", wire_digest="0xdef1")
    require_strict_trace(strict)
    expect_failure(lambda: require_strict_trace({**strict, "trace_mode": "fast-g4-unqualified"}),
                   "strict-same-cycle")
    expect_failure(lambda: require_strict_trace({**strict, "commit_digest": "OMITTED"}),
                   "commit_digest")
    expect_failure(lambda: require_strict_trace({**strict, "wire_digest": "OMITTED"}),
                   "wire_digest")
    text = "L32_OPENSBI_TIMEOUT " + " ".join(f"{k}={v}" for k, v in COMMON.items())
    got = summary(FakeLog(text + "\n"), "L32_OPENSBI_TIMEOUT")
    assert canonical(got, True) == canonical(got, False)
    assert canonical(got, False)["recent-pcs"] == "2147483904,2147483908"
    expect_failure(lambda: summary(FakeLog(""), "L32_OPENSBI_TIMEOUT"), "expected exactly one")
    expect_failure(lambda: summary(FakeLog(text + "\n" + text), "L32_OPENSBI_TIMEOUT"), "expected exactly one")
    expect_failure(lambda: canonical({k: v for k, v in got.items() if k != "ram-fingerprint"}, True), "ram-fingerprint")
    expect_failure(lambda: canonical({**got, "recent-pcs": "garbage"}, False), "recent retire PCs")
    differ = canonical({**got, "commits": "999"}, True)
    assert differ != canonical(got, False), "same-cycle commit discrepancy must be caught"
    differ = canonical({**got, "uart-fingerprint": "0xdeadbeef"}, True)
    assert differ != canonical(got, False), "UART discrepancy must be caught"
    differ = canonical({**got, "ram-fingerprint": "0x3333"}, True)
    assert differ != canonical(got, False), "RAM discrepancy must be caught"
    print("PRODUCT_GSIM_G3_COMPARE_SELFTEST_PASS positive=2 negatives=10 strict_trace=1")


if __name__ == "__main__":
    main()
