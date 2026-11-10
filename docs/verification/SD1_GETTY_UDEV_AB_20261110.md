# SD1 slow-udev candidate: bounded QEMU A/B evidence, 2026-11-10

## What is established (and what is not)

Three independent, cached-media QEMU A/B executions reproduced the same
device/getty service behavior.  Each used **110 seconds of guest udev-trigger
delay** in both arms.  The control removed the SD1-only systemd
`DefaultDeviceTimeoutSec=10min` drop-in, reverting to the normal 90-second
device deadline; the treatment preserved that exact drop-in.  The original
candidate SD, ext4 partition and QEMU Linux Image hashes were checked before
and after the experiments and reported unchanged.  Each guest booted an ext4
root and real `/sbin/init`.

**Positive finding, QEMU only:** the 90-second control timed out waiting for
`dev-ttyS0.device` and `serial-getty` dependency failed, whereas the 10-minute
treatment found the device and started `serial-getty@ttyS0.service`.  This is
evidence that increasing the systemd device deadline fixes **that getty
dependency failure under this synthetic delay**.

**Unqualified findings:** this does not prove Product RTL/SPI-SD boot, nor an
authenticated login.  A visible `localhost login:` line alone also does not
prove its provider: the Debian-derived image starts `console-getty.service`
alongside `serial-getty@ttyS0.service`.  Even the third diagnostic's QEMU
kernel command line `systemd.getty_auto=no systemd.mask=console-getty.service`
did not prevent the guest from starting `console-getty.service`.  The strict
service/login **provider-provenance gate remains FAIL**.  Do not call any of
these red Actions jobs a complete SD1 PASS.

## Reproducible GitHub evidence

| Diagnostic run | Control 90s: device/getty failure | Treatment 10min: serial-getty started | Wall time control / treatment | Artifact ZIP SHA256 |
| --- | --- | --- | --- | --- |
| [42835612436](https://github.com/yituanxing/minicore/actions/runs/42835612436) | yes / yes (service name ellipsized; initial parser missed it) | yes | 132.119s / 131.761s | `a5ce6738636d631e6e2d150c64db8dcb7963014b28a3c523c0585c8a18abd8b2` |
| [42837398493](https://github.com/yituanxing/minicore/actions/runs/42837398493) | yes / yes | yes | 93.890s / 129.882s | `fb4cdc0f32f1aeada59555ba538b1699324f43ddd38ee0c657044d33110f8829` |
| [42839402970](https://github.com/yituanxing/minicore/actions/runs/42839402970) | yes / yes | yes | 94.143s / 132.215s | `4bcdad3078713f69837e8f75bfbfa56be78b27b91ca22677f7a642b55a740895` |

Use the artifact ZIPs from these runs, not a screenshot or the green status
of a different job.  Each artifact includes `result.json`,
`baseline-uart.log`, `extended-uart.log`, and two per-arm QEMU result files.
The first source-QEMU native login proof is
[42831310558](https://github.com/yituanxing/minicore/actions/runs/42831310558).
The A/B reports independently record the same identities:

| Frozen input | SHA256 |
| --- | --- |
| Original candidate SD | `521e2cb8d2955f480d4a8a5405233eff074d919c7f2337cf957275e323c44217` |
| Original candidate ext4 partition | `46ec022fd0c1a18c50224a38c0aab9b9769f5de0c9c85aa97c23ec58f57e0922` |
| QEMU Linux Image | `f2469071bed3671a2a469e151e682c1efa3a4d00da6ac92b0c91b6256570f06c` |

The individual modified clone hashes are recorded inside each `result.json`,
not independently recomputable from the ZIPs because we intentionally did not
upload `.img` or `.ext4` media.  These are in-run SHA checks, not a public
copy of those disk bytes.

## False negative and provenance hazard

The first control UART explicitly contains:

```
[ TIME ] Timed out waiting for device dev-ttyS0.device - /dev/ttyS0.
[DEPEND] Dependency failed for serial-getty…S0.service - Serial Getty on ttyS0.
```

The original exact-string detector required the untruncated unit name, so it
falsely recorded `serial_getty_dependency_failed=false`.  The regression
helper `has_serial_getty_dependency_failure` now requires the same UART line
to identify both the dependency failure and the ttyS0 serial-getty target,
including systemd's abbreviated display.  It does **not** interpret a generic
systemd failure as this failure.

The first control also reaches `localhost login:` via the competing
`console-getty` path after serial-getty has failed.  Therefore neither a bare
login prompt nor QEMU boot success can certify the intended serial getty.
In the third control, startup stops at the 90-second dependency failure
without a login.  In the third treatment, serial-getty starts and login
appears, but `console-getty` also starts, leaving provider provenance open.

## Safe next step

For a fourth strict login-provider experiment, the isolated A/B preparer now
creates and verifies a **clone-only** systemd unit mask
`/etc/systemd/system/console-getty.service -> /dev/null` in **both** diagnostic
ext4 partitions, then checks that the separately enabled serial-getty symlink
remains present.  The next bounded A/B must actually prove this mask took
effect in UART before claiming that the login source was isolated.  The
three earlier CI verdicts remain red and unchanged.  Do not change the original candidate/signed media
or weaken the validation merely to turn CI green.  A qualified independent
Product RTL run with the *new* SD identity remains necessary for final SD1
service/login acceptance.  Existing signed V6 checkpoints have bound SD,
rootfs, DDR, and SD-COW state and cannot be reused with the replacement disk.
