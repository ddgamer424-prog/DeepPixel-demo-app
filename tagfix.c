// Copyright (c) 2026 DDgamer. All rights reserved.
// Loaded into the Java server process (LD_PRELOAD). Android 11+ tags heap pointers, and Java
// aborts with "Pointer tag ... was truncated". This switches that tagging off for this process only.
#include <malloc.h>

#ifndef M_BIONIC_SET_HEAP_TAGGING_LEVEL
#define M_BIONIC_SET_HEAP_TAGGING_LEVEL (-204)
#endif
extern int mallopt(int option, int value);

__attribute__((constructor(101))) static void deeppixel_tagfix(void) {
    mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, 0 /* M_HEAP_TAGGING_LEVEL_NONE */);
}
