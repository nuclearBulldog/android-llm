// AArch64 Linux VM page-cache guard (fadvise64) and thread-latency (prctl + setpriority + barriers) routines.
.text
.align 4

.global Java_com_slopware_androidllm_hardware_Arm64Tuner_nativeFadviseModelFd
    .inst 0x2a0203e0 // mov  w0, w2 (fd)
    .inst 0xaa0303e1 // mov  x1, x3 (offset)
    .inst 0xaa0403e2 // mov  x2, x4 (len)
    .inst 0x2a0503e3 // mov  w3, w5 (advice)
    .inst 0xd2801be8 // mov  x8, #223 (__NR_fadvise64)
    .inst 0xd4000001 // svc  #0
    .inst 0xd65f03c0 // ret

.global Java_com_slopware_androidllm_hardware_Arm64Tuner_nativeConfigureThreadLatency
    .inst 0xd10043ff // sub  sp, sp, #16
    .inst 0xa9000fe2 // stp  x2, x3, [sp]
    .inst 0x528003a0 // mov  w0, #29 (PR_SET_TIMERSLACK)
    .inst 0xaa0203e1 // mov  x1, x2 (timerSlackNs)
    .inst 0xd2800002 // mov  x2, #0
    .inst 0xd2800003 // mov  x3, #0
    .inst 0xd2800004 // mov  x4, #0
    .inst 0xd28014e8 // mov  x8, #167 (__NR_prctl)
    .inst 0xd4000001 // svc  #0
    .inst 0xb90003e0 // str  w0, [sp] (save prctl rc)
    .inst 0x52800000 // mov  w0, #0 (PRIO_PROCESS)
    .inst 0x52800001 // mov  w1, #0 (who=self)
    .inst 0xb9800be2 // ldrsw x2, [sp, #8] (niceValue)
    .inst 0xd2801188 // mov  x8, #140 (__NR_setpriority)
    .inst 0xd4000001 // svc  #0
    .inst 0xd5033b9f // dsb  ish
    .inst 0xd5033fdf // isb
    .inst 0xb94003e1 // ldr  w1, [sp]
    .inst 0xaa008020 // orr  x0, x1, x0, lsl #32
    .inst 0x910043ff // add  sp, sp, #16
    .inst 0xd65f03c0 // ret
