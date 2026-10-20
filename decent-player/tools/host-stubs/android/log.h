// Host stub of Android's liblog for tools/natural-join-test.cpp: the native engine and the USB output
// log through __android_log_print; the test defines it (silent unless SIM_LOG is set).
#pragma once
#include <stdarg.h>
#include <stdio.h>
#ifdef __cplusplus
extern "C" {
#endif
enum { ANDROID_LOG_VERBOSE = 2, ANDROID_LOG_DEBUG, ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR };
int __android_log_print(int prio, const char *tag, const char *fmt, ...);
void __android_log_assert(const char *cond, const char *tag, const char *fmt, ...);
#ifdef __cplusplus
}
#endif
