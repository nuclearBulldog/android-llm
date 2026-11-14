// Hand-verified AArch64 machine-code routines for Arm64Tuner silicon tuning & NEON bench.
.text
.align 4

.global Java_com_slopware_androidllm_hardware_Arm64Tuner_nativeSetFpcrFlushToZero
    .inst 0xd53b4400 // mrs  x0, fpcr
    .inst 0x52a06009 // movz w9, #0x0300, lsl #16 (bit 24 FZ + bit 25 DN)
    .inst 0x34000062 // cbz  w2, +12 (-> bic)
    .inst 0xaa090000 // orr  x0, x0, x9
    .inst 0x14000002 // b    +8 (-> msr)
    .inst 0x8a290000 // bic  x0, x0, x9
    .inst 0xd51b4400 // msr  fpcr, x0
    .inst 0xd53b4400 // mrs  x0, fpcr
    .inst 0xd65f03c0 // ret

.global Java_com_slopware_androidllm_hardware_Arm64Tuner_nativeReadSiliconRegs
    .inst 0xd53b0020 // mrs  x0, ctr_el0
    .inst 0x2a0003e0 // mov  w0, w0 (zero upper 32 bits)
    .inst 0xd53be001 // mrs  x1, cntfrq_el0
    .inst 0xaa018000 // orr  x0, x0, x1, lsl #32
    .inst 0xd65f03c0 // ret

.global Java_com_slopware_androidllm_hardware_Arm64Tuner_nativePinThreadAffinity
    .inst 0xd10043ff // sub  sp, sp, #16
    .inst 0xa9007fe2 // stp  x2, xzr, [sp]
    .inst 0xd2800000 // mov  x0, #0 (current tid)
    .inst 0xd2800101 // mov  x1, #8 (sizeof cpu_set_t mask)
    .inst 0x910003e2 // mov  x2, sp
    .inst 0xd2800f48 // mov  x8, #122 (__NR_sched_setaffinity)
    .inst 0xd4000001 // svc  #0
    .inst 0x910043ff // add  sp, sp, #16
    .inst 0xd65f03c0 // ret

.global Java_com_slopware_androidllm_hardware_Arm64Tuner_nativeNeonDotBench
    .inst 0x52800029 // mov  w9, #1
    .inst 0x7100005f // cmp  w2, #0
    .inst 0x1a89c042 // csel w2, w2, w9, gt
    .inst 0x1e2e1000 // fmov s0, #1.0
    .inst 0x4e040400 // dup  v0.4s, v0.s[0]
    .inst 0x4ea01c01 // orr  v1.16b, v0.16b, v0.16b
    .inst 0x4ea01c02 // orr  v2.16b, v0.16b, v0.16b
    .inst 0xd53be043 // mrs  x3, cntvct_el0
    .inst 0xf98003e0 // prfm pldl1keep, [sp]
    .inst 0x4e22cc20 // fmla v0.4s, v1.4s, v2.4s (1/8)
    .inst 0x4e22cc20 // fmla v0.4s, v1.4s, v2.4s (2/8)
    .inst 0x4e22cc20 // fmla v0.4s, v1.4s, v2.4s (3/8)
    .inst 0x4e22cc20 // fmla v0.4s, v1.4s, v2.4s (4/8)
    .inst 0x4e22cc20 // fmla v0.4s, v1.4s, v2.4s (5/8)
    .inst 0x4e22cc20 // fmla v0.4s, v1.4s, v2.4s (6/8)
    .inst 0x4e22cc20 // fmla v0.4s, v1.4s, v2.4s (7/8)
    .inst 0x4e22cc20 // fmla v0.4s, v1.4s, v2.4s (8/8)
    .inst 0x71000442 // subs w2, w2, #1
    .inst 0x54fffeec // b.gt -36 (bit4=0 ARMv8.0+ B.cond)
    .inst 0xd53be044 // mrs  x4, cntvct_el0
    .inst 0xcb030080 // sub  x0, x4, x3
    .inst 0xd65f03c0 // ret
