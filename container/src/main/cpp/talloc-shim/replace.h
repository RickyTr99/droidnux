/*
 * Minimal stand-in for Samba's lib/replace, enough to build talloc.c with
 * the NDK without running talloc's waf configure step.
 */
#ifndef DROIDNUX_TALLOC_REPLACE_H
#define DROIDNUX_TALLOC_REPLACE_H

#include <errno.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

/* Must match the version of the vendored talloc.c / talloc.h. */
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 5
#define TALLOC_BUILD_VERSION_RELEASE 0

#define HAVE_SYS_AUXV_H 1
#define HAVE_GETAUXVAL 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_INTPTR_T 1
#define HAVE_VA_COPY 1

#define _PUBLIC_ __attribute__((visibility("default")))

#ifndef MIN
#define MIN(a, b) ((a) < (b) ? (a) : (b))
#endif

/* C23 memset_explicit() is not in bionic. */
static inline void *memset_explicit(void *s, int c, size_t n)
{
	memset(s, c, n);
	__asm__ __volatile__("" : : "r"(s) : "memory");
	return s;
}

#endif /* DROIDNUX_TALLOC_REPLACE_H */
