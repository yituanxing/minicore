/* SPDX-License-Identifier: Apache-2.0 */
int coremark_main(void);
void coremark_reset_status(void);
int coremark_status(void);

int main(void)
{
    coremark_reset_status();
    (void)coremark_main();
    return coremark_status();
}
