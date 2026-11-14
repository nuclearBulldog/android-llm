#include <jni.h>
#include <stdint.h>

/*
 * Reference C + AArch64 inline assembly for libslop_hw.so.
 * At build time, Gradle assembles app/src/main/asm/slop_silicon.asm and
 * app/src/main/asm/slop_vm_guard.asm into a 16KB-aligned ELF64 arm64-v8a
 * shared library directly so no NDK installation is required on the host.
 */

JNIEXPORT jlong JNICALL
Java_com_slopware_androidllm_hardware_Arm64Tuner_nativeSetFpcrFlushToZero(
    JNIEnv *env,
    jobject thiz,
    jboolean enable
) {
    (void)env;
    (void)thiz;
#ifdef __aarch64__
    uint64_t fpcr = 0;
    const uint64_t fz_dn_mask = (1ULL << 24) | (1ULL << 25); /* FPCR.FZ | FPCR.DN */
    __asm__ volatile("mrs %0, fpcr" : "=r"(fpcr));
    if (enable) {
        fpcr |= fz_dn_mask;
    } else {
        fpcr &= ~fz_dn_mask;
    }
    __asm__ volatile("msr fpcr, %0" :: "r"(fpcr));
    __asm__ volatile("mrs %0, fpcr" : "=r"(fpcr));
    return (jlong)fpcr;
#else
    return enable ? (jlong)((1ULL << 24) | (1ULL << 25)) : 0L;
#endif
}

JNIEXPORT jlong JNICALL
Java_com_slopware_androidllm_hardware_Arm64Tuner_nativeReadSiliconRegs(
    JNIEnv *env,
    jobject thiz
) {
    (void)env;
    (void)thiz;
#ifdef __aarch64__
    uint64_t ctr = 0;
    uint64_t cntfrq = 0;
    __asm__ volatile("mrs %0, ctr_el0" : "=r"(ctr));
    __asm__ volatile("mrs %0, cntfrq_el0" : "=r"(cntfrq));
    return (jlong)((ctr & 0xFFFFFFFFULL) | (cntfrq << 32));
#else
    return 0L;
#endif
}

JNIEXPORT jint JNICALL
Java_com_slopware_androidllm_hardware_Arm64Tuner_nativePinThreadAffinity(
    JNIEnv *env,
    jobject thiz,
    jlong mask
) {
    (void)env;
    (void)thiz;
#ifdef __aarch64__
    uint64_t cpu_mask = (uint64_t)mask;
    register long x0 __asm__("x0") = 0; /* current tid */
    register long x1 __asm__("x1") = (long)sizeof(cpu_mask);
    register long x2 __asm__("x2") = (long)&cpu_mask;
    register long x8 __asm__("x8") = 122; /* __NR_sched_setaffinity on arm64 */
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x8) : "memory");
    return (jint)x0;
#else
    (void)mask;
    return -1;
#endif
}

JNIEXPORT jlong JNICALL
Java_com_slopware_androidllm_hardware_Arm64Tuner_nativeNeonDotBench(
    JNIEnv *env,
    jobject thiz,
    jint loops
) {
    (void)env;
    (void)thiz;
#ifdef __aarch64__
    int32_t count = loops > 0 ? loops : 1;
    uint64_t t0 = 0;
    uint64_t t1 = 0;
    __asm__ volatile(
        "fmov s0, #1.0\n\t"
        "dup v0.4s, v0.s[0]\n\t"
        "orr v1.16b, v0.16b, v0.16b\n\t"
        "orr v2.16b, v0.16b, v0.16b\n\t"
        "mrs %0, cntvct_el0\n\t"
        "prfm pldl1keep, [sp]\n\t"
        "1:\n\t"
        "fmla v0.4s, v1.4s, v2.4s\n\t"
        "fmla v0.4s, v1.4s, v2.4s\n\t"
        "fmla v0.4s, v1.4s, v2.4s\n\t"
        "fmla v0.4s, v1.4s, v2.4s\n\t"
        "fmla v0.4s, v1.4s, v2.4s\n\t"
        "fmla v0.4s, v1.4s, v2.4s\n\t"
        "fmla v0.4s, v1.4s, v2.4s\n\t"
        "fmla v0.4s, v1.4s, v2.4s\n\t"
        "subs %w2, %w2, #1\n\t"
        "b.gt 1b\n\t"
        "mrs %1, cntvct_el0\n\t"
        : "=&r"(t0), "=&r"(t1), "+r"(count)
        :
        : "v0", "v1", "v2", "cc", "memory"
    );
    return (jlong)(t1 - t0);
#else
    (void)loops;
    return 0L;
#endif
}

JNIEXPORT jint JNICALL
Java_com_slopware_androidllm_hardware_Arm64Tuner_nativeFadviseModelFd(
    JNIEnv *env,
    jobject thiz,
    jint fd,
    jlong offset,
    jlong len,
    jint advice
) {
    (void)env;
    (void)thiz;
#ifdef __aarch64__
    register long x0 __asm__("x0") = (long)fd;
    register long x1 __asm__("x1") = (long)offset;
    register long x2 __asm__("x2") = (long)len;
    register long x3 __asm__("x3") = (long)advice;
    register long x8 __asm__("x8") = 223; /* __NR_fadvise64 on arm64 */
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x8) : "memory");
    return (jint)x0;
#else
    (void)fd;
    (void)offset;
    (void)len;
    (void)advice;
    return -1;
#endif
}

JNIEXPORT jlong JNICALL
Java_com_slopware_androidllm_hardware_Arm64Tuner_nativeConfigureThreadLatency(
    JNIEnv *env,
    jobject thiz,
    jlong timerSlackNs,
    jint niceValue
) {
    (void)env;
    (void)thiz;
#ifdef __aarch64__
    register long x0 __asm__("x0") = 29; /* PR_SET_TIMERSLACK */
    register long x1 __asm__("x1") = (long)timerSlackNs;
    register long x2 __asm__("x2") = 0;
    register long x3 __asm__("x3") = 0;
    register long x4 __asm__("x4") = 0;
    register long x8 __asm__("x8") = 167; /* __NR_prctl */
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x8) : "memory");
    uint32_t prctl_rc = (uint32_t)(int32_t)x0;

    register long p0 __asm__("x0") = 0; /* PRIO_PROCESS */
    register long p1 __asm__("x1") = 0; /* self */
    register long p2 __asm__("x2") = (long)niceValue;
    register long p8 __asm__("x8") = 140; /* __NR_setpriority */
    __asm__ volatile("svc #0\n\tdsb ish\n\tisb" : "+r"(p0) : "r"(p1), "r"(p2), "r"(p8) : "memory");
    uint64_t setprio_rc = (uint64_t)(uint32_t)(int32_t)p0;
    return (jlong)(((uint64_t)prctl_rc) | (setprio_rc << 32));
#else
    (void)timerSlackNs;
    (void)niceValue;
    return -1L;
#endif
}
