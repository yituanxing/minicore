#!/usr/bin/env python3
"""Small real-RTL G5 direct-seed source-opcode-only Sv39 fixture.

No MRET, restore overlay, or changes to the guest DDR: the standalone Product
warm-seed top gets PC/privilege/SATP/PMP/SP via simulation-only state pins.
"""

import argparse
import json
from pathlib import Path

try:
    from . import p1_rvc_fixture as rvc
except ImportError:
    import p1_rvc_fixture as rvc


def prepare(output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    root, l1 = rvc.table_pages()
    with (output / 'ram-256m.bin').open('wb') as image:
        image.truncate(rvc.RAM_BYTES)
        image.seek(rvc.PHYSICAL_PC - rvc.RAM_BASE)
        image.write(rvc.INSTRUCTION_AND_SENTINEL)
        image.seek(rvc.ROOT_PA - rvc.RAM_BASE)
        image.write(root)
        image.seek(rvc.HIGH_L1_PA - rvc.RAM_BASE)
        image.write(l1)
    expected = rvc.expected()
    expected.update({
        'schema': 'aethercore.c1.g5-direct-seed-rvc.synthetic-sv39.v1',
        'real_product_top': 'AetherCoreProductDebianWarmSeedRV64SimTop',
        'restoration_mechanism': 'isolated RTL warmHold/direct architectural seed',
        'mret_executed': False,
        'guest_ddr_overlaid': False,
        'source_is_full_qemu_snapshot': False,
        'qualification': 'SYNTHETIC_PRODUCT_G5_DIRECT_SEED_RVC_ONLY_NOT_QEMU_OR_SD1',
    })
    (output / 'expected.json').write_text(
        json.dumps(expected, sort_keys=True, indent=2) + '\n')
    print('PRODUCT_C1_G5_DIRECT_SEED_SYNTHETIC_FIXTURE_PREPARED_NOT_RTL_PASS')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out-dir', type=Path, required=True)
    args = parser.parse_args()
    prepare(args.out_dir)


if __name__ == '__main__':
    main()
