/*
 * Path syscalls without a proot stop, preloaded into the Plasma session in
 * the container.
 *
 * Under proot every syscall with a path stops the calling thread: proot reads
 * the path, translates it into the rootfs and lets the syscall go on. That
 * costs ~70 us of proot's CPU (one thread for the whole session) and as much
 * waiting for the caller. Opening a KDE app makes tens of thousands of them,
 * nearly all to read files: open for reading, stat (also of descriptors),
 * access, readlink and realpath (glibc's reads every component).
 *
 * This library serves those itself for absolute paths in the rootfs. The
 * parent directory is opened with openat2() (RESOLVE_IN_ROOT,
 * RESOLVE_NO_SYMLINKS) from the rootfs, which proot opened for "/", and the
 * call is made on the last component. Symlinks are resolved here, one
 * component at a time, as the kernel would in the rootfs. These syscalls go
 * through a gadget in proot's loader, at a fixed address, that proot's
 * seccomp filter lets through (proot patch 0009); proot never sees them.
 *
 * Everything else goes to proot as before, with an ordinary syscall:
 * relative paths, /proc, /dev, /sys and /sdcard (proot's bindings),
 * link2symlink's files and links (shown as hard links), permission errors
 * (proot is a fake root), opens that write or create. Results match proot's:
 * files owned by the app's uid show as fake_id0 reports them. One difference:
 * proot lends the file it opened for a fake root a u+rw mode until its next
 * stop, and a descriptor's stat here can see that.
 *
 * Compiled with the NDK but loaded by glibc, like display/.../shm/dnx_shm.c:
 * it makes syscalls itself and calls only glibc functions whose ABI is plain C.
 */

#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>

#if !defined(__aarch64__)
#error "the syscall numbers and structures below are arm64's"
#endif

#define API __attribute__((visibility("default")))

extern int* __errno_location(void);
extern void* malloc(size_t size);
extern void* dlsym(void* handle, const char* name);
#define RTLD_NEXT ((void*) -1l)

/* -- Kernel interface (arm64) ----------------------------------------------- */

enum {
    NR_fcntl = 25, NR_faccessat = 48, NR_openat = 56, NR_close = 57, NR_readlinkat = 78, NR_newfstatat = 79,
    NR_fstat = 80, NR_getresuid = 148, NR_getresgid = 150, NR_getuid = 174, NR_getgid = 176, NR_mincore = 232,
    NR_statx = 291, NR_openat2 = 437, NR_faccessat2 = 439,
};

enum { ENOENT = 2, EINTR = 4, EBADF = 9, ENFILE = 23, EMFILE = 24, ENOTDIR = 20, EINVAL = 22, ENOSYS = 38, ELOOP = 40 };

#define AT_FDCWD (-100)
#define AT_SYMLINK_NOFOLLOW 0x100
#define AT_NO_AUTOMOUNT 0x800
#define AT_EMPTY_PATH 0x1000
#define AT_STATX_SYNC_TYPE 0x6000
#define O_ACCMODE 03
#define O_RDONLY 0
#define O_CREAT 0100
#define O_DIRECTORY 040000
#define O_NOFOLLOW 0100000
#define O_CLOEXEC 02000000
#define O_PATH 010000000
#define O_TMPFILE_BIT 020000000
#define F_DUPFD_CLOEXEC 1030
#define RESOLVE_NO_SYMLINKS 0x04
#define RESOLVE_IN_ROOT 0x10
#define S_IFMT 0170000
#define S_IFLNK 0120000
#define S_IFDIR 0040000
#define STATX_TYPE 0x1u
#define STATX_UID 0x8u
#define STATX_GID 0x10u
#define PATH_MAX 4096

struct open_how {
    uint64_t flags, mode, resolve;
};

/* struct stat of arm64 (asm-generic), as glibc's. */
struct kstat {
    uint64_t dev, ino;
    uint32_t mode, nlink, uid, gid;
    uint64_t rdev, pad1;
    int64_t size;
    int32_t blksize, pad2;
    int64_t blocks, times[6];
    uint32_t unused[2];
};

/* The head of struct statx. */
struct kstatx {
    uint32_t mask, blksize;
    uint64_t attributes;
    uint32_t nlink, uid, gid;
    uint16_t mode;
};

/* -- Syscalls ----------------------------------------------------------------- */

/* proot's loader is linked here (LOADER_ADDRESS in proot's arch.h); its first
 * pages stay mapped in every process proot starts. */
#define LOADER_ADDRESS 0x5400000000ul
#define LOADER_SCAN_PAGES 4
static const uint64_t gadget_magic[2] = {0x6467746e78646e64ull, 0x7465676461676e78ull};
#define INSN_SVC0 0xd4000001u
#define INSN_RET 0xd65f03c0u

static uintptr_t gadget;

/* A syscall proot sees, as glibc's would be. */
static long sys(long n, long a, long b, long c, long d, long e) {
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x8 __asm__("x8") = n;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x8) : "memory");
    return x0;
}

/* A syscall proot does not see: the paths are already the rootfs's. */
static long gsys(long n, long a, long b, long c, long d, long e) {
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x8 __asm__("x8") = n;
    register uintptr_t x16 __asm__("x16") = gadget;
    __asm__ volatile("blr x16"
                     : "+r"(x0)
                     : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x8), "r"(x16)
                     : "x30", "memory");
    return x0;
}

static long set_errno(long result) {
    if (result < 0 && result > -4096) {
        *__errno_location() = (int) -result;
        return -1;
    }
    return result;
}

/* -- State -------------------------------------------------------------------- */

enum { OFF_UNKNOWN, OFF_STARTING, ON, OFF };

static int state; /* atomic */
static int root_fd = -1;
static uint64_t root_dev, root_ino;
static char host_root[PATH_MAX];
static long host_root_length;
static uint32_t real_uid, real_gid;
static uint32_t fake_uid, fake_gid; /* atomic */

static int find_gadget(void) {
    for (int page = 0; page < LOADER_SCAN_PAGES; page++) {
        uintptr_t base = LOADER_ADDRESS + (uintptr_t) page * 4096;
        unsigned char resident;
        if (sys(NR_mincore, (long) base, 4096, (long) &resident, 0, 0) < 0) break; /* not mapped */
        for (uintptr_t at = base; at + 24 <= base + 4096; at += 16) {
            const uint64_t* words = (const uint64_t*) at;
            const uint32_t* code = (const uint32_t*) (at + 16);
            if (words[0] == gadget_magic[0] && words[1] == gadget_magic[1] && code[0] == INSN_SVC0 &&
                code[1] == INSN_RET) {
                gadget = at + 16;
                return 1;
            }
        }
    }
    return 0;
}

/* The ids fake_id0 reports for files the app owns: the saved set ids. */
static void read_fake_ids(void) {
    uint32_t r, e, s;
    if (sys(NR_getresuid, (long) &r, (long) &e, (long) &s, 0, 0) == 0) __atomic_store_n(&fake_uid, s, __ATOMIC_RELAXED);
    if (sys(NR_getresgid, (long) &r, (long) &e, (long) &s, 0, 0) == 0) __atomic_store_n(&fake_gid, s, __ATOMIC_RELAXED);
}

static int start(void) {
    if (!find_gadget()) return 0;
    /* Through the gadget, fake_id0 cannot answer: the real uid means proot
     * did not see the call, so its filter has the gadget. */
    real_uid = (uint32_t) gsys(NR_getuid, 0, 0, 0, 0, 0);
    real_gid = (uint32_t) gsys(NR_getgid, 0, 0, 0, 0, 0);
    if (real_uid == 0) return 0;

    long fd = sys(NR_openat, AT_FDCWD, (long) "/", O_PATH | O_DIRECTORY | O_CLOEXEC, 0, 0);
    if (fd < 0) return 0;
    /* Out of the way of programs that expect the lowest free descriptors. */
    long high = gsys(NR_fcntl, fd, F_DUPFD_CLOEXEC, 700, 0, 0);
    gsys(NR_close, fd, 0, 0, 0, 0);
    if (high < 0) return 0;
    struct kstat st;
    char link[32] = "/proc/self/fd/";
    char* p = link + 14;
    char digits[8];
    int n = 0;
    for (long v = high; v > 0 && n < 7; v /= 10) digits[n++] = (char) ('0' + v % 10);
    while (n > 0) *p++ = digits[--n];
    *p = 0;
    host_root_length = gsys(NR_readlinkat, AT_FDCWD, (long) link, (long) host_root, sizeof(host_root) - 1, 0);
    if (gsys(NR_fstat, high, (long) &st, 0, 0, 0) < 0 || host_root_length <= 1 ||
        host_root_length >= (long) sizeof(host_root) - 1) {
        gsys(NR_close, high, 0, 0, 0, 0);
        return 0;
    }
    host_root[host_root_length] = 0;
    root_fd = (int) high;
    root_dev = st.dev;
    root_ino = st.ino;
    read_fake_ids();
    return 1;
}

/* Whether the fast path can be taken now. */
static int on(void) {
    int s = __atomic_load_n(&state, __ATOMIC_ACQUIRE);
    if (s == OFF_UNKNOWN) {
        if (!__atomic_compare_exchange_n(&state, &s, OFF_STARTING, 0, __ATOMIC_ACQUIRE, __ATOMIC_RELAXED)) return 0;
        s = start() ? ON : OFF;
        __atomic_store_n(&state, s, __ATOMIC_RELEASE);
    }
    if (s != ON) return 0;
    /* The program may have closed the descriptor, or put another file on its
     * number (closing every descriptor before exec, dup2). */
    struct kstat st;
    if (gsys(NR_fstat, root_fd, (long) &st, 0, 0, 0) < 0 || st.dev != root_dev || st.ino != root_ino) {
        __atomic_store_n(&state, OFF, __ATOMIC_RELEASE);
        return 0;
    }
    return 1;
}

/* A sandbox's own seccomp filter refused one of our syscalls (Firefox's
 * content processes): leave everything to proot and the sandbox from now on. */
static void refused(long result) {
    if (result == -ENOSYS || result == -1 /* EPERM */) __atomic_store_n(&state, OFF, __ATOMIC_RELEASE);
}

/* -- Paths ---------------------------------------------------------------------- */

static int same(const char* a, long length, const char* b) {
    for (long i = 0; i < length; i++)
        if (a[i] != b[i]) return 0;
    return b[length] == 0;
}

static int starts_with(const char* s, const char* prefix) {
    while (*prefix)
        if (*s++ != *prefix++) return 0;
    return 1;
}

static long length_of(const char* s) {
    long n = 0;
    while (s[n]) n++;
    return n;
}

/* Whether the first component @c (@length bytes) is one of proot's bindings. */
static int bound(const char* c, long length) {
    return same(c, length, "proc") || same(c, length, "sys") || same(c, length, "dev") || same(c, length, "sdcard");
}

/* Whether @name is a file of link2symlink, which proot reports differently. */
static int l2s_name(const char* name) { return starts_with(name, ".l2s.") || starts_with(name, ".proot.l2s."); }

/* Whether the symlink content @buf (@length bytes) names a host path in the
 * rootfs, which proot shows as a guest path (link2symlink's links). */
static int in_host_root(const char* buf, long length) {
    if (length < host_root_length) return 0;
    for (long i = 0; i < host_root_length; i++)
        if (buf[i] != host_root[i]) return 0;
    return length == host_root_length || buf[host_root_length] == '/';
}

/*
 * Checks that @path is one the kernel resolves in the rootfs as proot does:
 * absolute, without empty, "." or ".." components or a trailing slash, outside
 * proot's bindings, not a link2symlink file. Returns the offset of its last
 * component, or -1.
 */
static long plain_path(const char* path) {
    if (path == 0 || path[0] != '/') return -1;
    long last = 1, i = 1;
    for (int first = 1;; first = 0) {
        long start = i;
        while (path[i] != 0 && path[i] != '/') i++;
        long length = i - start;
        const char* c = path + start;
        if (length == 0 || same(c, length, ".") || same(c, length, "..")) return -1;
        if (first && bound(c, length)) return -1;
        last = start;
        if (path[i] == 0) break;
        i++;
        if (i >= PATH_MAX) return -1;
    }
    return l2s_name(path + last) ? -1 : last;
}

/* Below -4095, apart from -errno and descriptors. */
#define FALLBACK (-10000) /* proot has to answer */
#define SYMLINK (-10001)  /* a directory of the path is a symlink */

/*
 * Opens the directory holding the plain @path, whose last component starts
 * at @last. Returns a descriptor, -errno for a result proot would give too,
 * SYMLINK or FALLBACK.
 */
static long open_parent(const char* path, long last) {
    if (last == 1) return root_fd;
    char parent[PATH_MAX];
    for (long i = 0; i < last - 1; i++) parent[i] = path[i];
    parent[last - 1] = 0;
    struct open_how how = {O_PATH | O_DIRECTORY | O_CLOEXEC, 0, RESOLVE_IN_ROOT | RESOLVE_NO_SYMLINKS};
    long fd = gsys(NR_openat2, root_fd, (long) parent, (long) &how, sizeof(how), 0);
    if (fd >= 0 || fd == -ENOENT || fd == -ENOTDIR) return fd;
    if (fd == -ELOOP) return SYMLINK;
    refused(fd);
    return FALLBACK; /* EACCES: fake root's business */
}

static void close_parent(long fd) {
    if (fd != root_fd) gsys(NR_close, fd, 0, 0, 0, 0);
}

/*
 * Resolves the symlinks in the absolute guest @path into @out, as the kernel
 * would in the rootfs, the last component's only if @follow. Each component is
 * looked up from the rootfs through plain directories only. Returns 0, -errno
 * for a result proot would give too, or FALLBACK (bindings, link2symlink).
 */
static long resolve(const char* path, int follow, char* out) {
    char rest[PATH_MAX], target[PATH_MAX];
    long rest_length = length_of(path);
    if (rest_length >= PATH_MAX) return FALLBACK;
    for (long i = 0; i <= rest_length; i++) rest[i] = path[i];
    long n = 1, at = 0, links = 0;
    out[0] = '/';
    out[1] = 0;
    for (;;) {
        while (rest[at] == '/') at++;
        if (rest[at] == 0) return 0;
        long start = at;
        while (rest[at] != 0 && rest[at] != '/') at++;
        long length = at - start;
        const char* c = rest + start;
        if (same(c, length, ".")) continue;
        if (same(c, length, "..")) {
            while (n > 1 && out[n - 1] != '/') n--;
            if (n > 1) n--;
            out[n] = 0;
            continue;
        }
        if (n == 1 && bound(c, length)) return FALLBACK;
        if (n + 1 + length >= PATH_MAX) return FALLBACK;
        long previous = n;
        if (n > 1) out[n++] = '/';
        for (long i = 0; i < length; i++) out[n++] = c[i];
        out[n] = 0;
        int last = rest[at] == 0;
        if (last && !follow) return 0;

        struct kstat st;
        long r = gsys(NR_newfstatat, root_fd, (long) (out + 1), (long) &st, AT_SYMLINK_NOFOLLOW, 0);
        if (r == -ENOENT || r == -ENOTDIR) return r;
        if (r < 0) {
            refused(r);
            return FALLBACK;
        }
        if ((st.mode & S_IFMT) == S_IFLNK) {
            if (++links > 40) return -ELOOP;
            long t = gsys(NR_readlinkat, root_fd, (long) (out + 1), (long) target, sizeof(target) - 1, 0);
            if (t <= 0 || in_host_root(target, t)) return FALLBACK;
            /* The rest of the path now follows the link's content. */
            long tail = rest_length - at;
            if (t + tail >= PATH_MAX) return FALLBACK;
            for (long i = 0; i <= tail; i++) target[t + i] = rest[at + i];
            rest_length = t + tail;
            for (long i = 0; i <= rest_length; i++) rest[i] = target[i];
            at = 0;
            n = target[0] == '/' ? 1 : previous;
            out[n] = 0;
            continue;
        }
        if (!last && (st.mode & S_IFMT) != S_IFDIR) return -ENOTDIR;
        if (last) return 0;
    }
}

/* The directory holding the plain @path, for a call on *@base. */
static long lookup_plain(const char* path, const char** base) {
    long last = plain_path(path);
    if (last < 0) return FALLBACK;
    long dir = open_parent(path, last);
    *base = path + last;
    return dir == SYMLINK ? FALLBACK : dir; /* changed meanwhile */
}

/*
 * Opens the directory holding @path for a call on its last component, which
 * *@base points to, resolving symlinks in the directories (into @out).
 */
static long lookup(const char* path, char* out, const char** base) {
    if (!on()) return FALLBACK;
    long last = plain_path(path);
    if (last >= 0) {
        long dir = open_parent(path, last);
        *base = path + last;
        if (dir != SYMLINK) return dir;
    } else if (path == 0 || path[0] != '/' || path[length_of(path) - 1] == '/') {
        return FALLBACK;
    }
    long r = resolve(path, 0, out);
    return r != 0 ? r : lookup_plain(out, base);
}

/*
 * Opens the directory holding the file @path names, the symlink itself
 * unless @follow, and stats the file into @st. Returns the directory, with
 * *@base the file's name, -errno or FALLBACK.
 */
static long final(const char* path, char* out, const char** base, struct kstat* st, int follow) {
    long dir = lookup(path, out, base);
    for (int round = 0; dir >= 0; round++) {
        long r = gsys(NR_newfstatat, dir, (long) *base, (long) st, AT_SYMLINK_NOFOLLOW, 0);
        if (r == 0 && (st->mode & S_IFMT) == S_IFLNK) {
            if (follow && round == 0) {
                close_parent(dir);
                r = resolve(path, 1, out);
                dir = r != 0 ? r : lookup_plain(out, base);
                continue;
            }
            /* proot shows link2symlink's links as the files they stand for. */
            char target[PATH_MAX];
            long t = gsys(NR_readlinkat, dir, (long) *base, (long) target, sizeof(target), 0);
            if (follow || t <= 0 || t >= (long) sizeof(target) || in_host_root(target, t)) r = FALLBACK;
        }
        if (r == 0) return dir;
        close_parent(dir);
        if (r == -ENOENT || r == -ENOTDIR) return r;
        if (r != FALLBACK) refused(r);
        return FALLBACK;
    }
    return dir;
}

static void fake_ids(uint32_t* uid, uint32_t* gid) {
    if (*uid == real_uid) *uid = __atomic_load_n(&fake_uid, __ATOMIC_RELAXED);
    if (*gid == real_gid) *gid = __atomic_load_n(&fake_gid, __ATOMIC_RELAXED);
}

/* Whether proot reports the descriptor @fd as the kernel does: it is not open
 * on a link2symlink file. */
static int plain_fd(int fd) {
    if (fd < 0) return 0;
    char link[32] = "/proc/self/fd/", digits[12], target[PATH_MAX];
    long n = 14, d = 0;
    for (int v = fd; v > 0 || d == 0; v /= 10) digits[d++] = (char) ('0' + v % 10);
    while (d > 0) link[n++] = digits[--d];
    link[n] = 0;
    long t = gsys(NR_readlinkat, AT_FDCWD, (long) link, (long) target, sizeof(target) - 1, 0);
    if (t <= 0) return 0;
    target[t] = 0;
    long name = t;
    while (name > 0 && target[name - 1] != '/') name--;
    return !l2s_name(target + name);
}

/* -- stat ------------------------------------------------------------------------- */

static long fd_stat(int fd, struct kstat* st) {
    if (!on()) return FALLBACK;
    long r = gsys(NR_fstat, fd, (long) st, 0, 0, 0);
    if (r == 0 && plain_fd(fd)) {
        fake_ids(&st->uid, &st->gid);
        return 0;
    }
    if (r != 0) refused(r);
    return r == -EBADF ? r : FALLBACK;
}

static int do_fstatat(int dirfd, const char* path, struct kstat* st, int flags) {
    long r = FALLBACK;
    if (path != 0 && path[0] == 0 && (flags & ~AT_SYMLINK_NOFOLLOW) == AT_EMPTY_PATH) {
        r = fd_stat(dirfd, st);
    } else if ((flags & ~AT_SYMLINK_NOFOLLOW) == 0) {
        char out[PATH_MAX];
        const char* base;
        long dir = final(path, out, &base, st, !(flags & AT_SYMLINK_NOFOLLOW));
        if (dir >= 0) {
            close_parent(dir);
            fake_ids(&st->uid, &st->gid);
            r = 0;
        } else {
            r = dir;
        }
    }
    if (r != FALLBACK) return (int) set_errno(r);
    return (int) set_errno(sys(NR_newfstatat, dirfd, (long) path, (long) st, flags, 0));
}

API int fstatat(int dirfd, const char* path, struct kstat* st, int flags) { return do_fstatat(dirfd, path, st, flags); }
API int fstatat64(int dirfd, const char* path, struct kstat* st, int flags) { return do_fstatat(dirfd, path, st, flags); }
API int stat(const char* path, struct kstat* st) { return do_fstatat(AT_FDCWD, path, st, 0); }
API int stat64(const char* path, struct kstat* st) { return do_fstatat(AT_FDCWD, path, st, 0); }
API int lstat(const char* path, struct kstat* st) { return do_fstatat(AT_FDCWD, path, st, AT_SYMLINK_NOFOLLOW); }
API int lstat64(const char* path, struct kstat* st) { return do_fstatat(AT_FDCWD, path, st, AT_SYMLINK_NOFOLLOW); }
API int fstat(int fd, struct kstat* st) {
    long r = fd_stat(fd, st);
    return (int) set_errno(r != FALLBACK ? r : sys(NR_fstat, fd, (long) st, 0, 0, 0));
}
API int fstat64(int fd, struct kstat* st) { return fstat(fd, st); }
/* Programs built against glibc < 2.33. */
API int __xstat(int v, const char* path, struct kstat* st) { (void) v; return do_fstatat(AT_FDCWD, path, st, 0); }
API int __xstat64(int v, const char* path, struct kstat* st) { (void) v; return do_fstatat(AT_FDCWD, path, st, 0); }
API int __lxstat(int v, const char* path, struct kstat* st) {
    (void) v;
    return do_fstatat(AT_FDCWD, path, st, AT_SYMLINK_NOFOLLOW);
}
API int __lxstat64(int v, const char* path, struct kstat* st) {
    (void) v;
    return do_fstatat(AT_FDCWD, path, st, AT_SYMLINK_NOFOLLOW);
}
API int __fxstat(int v, int fd, struct kstat* st) { (void) v; return fstat(fd, st); }
API int __fxstat64(int v, int fd, struct kstat* st) { (void) v; return fstat(fd, st); }
API int __fxstatat(int v, int dirfd, const char* path, struct kstat* st, int flags) {
    (void) v;
    return do_fstatat(dirfd, path, st, flags);
}
API int __fxstatat64(int v, int dirfd, const char* path, struct kstat* st, int flags) {
    (void) v;
    return do_fstatat(dirfd, path, st, flags);
}

static void fake_statx_ids(struct kstatx* stx) {
    uint32_t uid = (stx->mask & STATX_UID) ? stx->uid : ~0u;
    uint32_t gid = (stx->mask & STATX_GID) ? stx->gid : ~0u;
    fake_ids(&uid, &gid);
    if (stx->mask & STATX_UID) stx->uid = uid;
    if (stx->mask & STATX_GID) stx->gid = gid;
}

API int statx(int dirfd, const char* path, int flags, unsigned mask, struct kstatx* stx) {
    long r = FALLBACK;
    int options = flags & ~(AT_SYMLINK_NOFOLLOW | AT_NO_AUTOMOUNT | AT_STATX_SYNC_TYPE);
    if (path != 0 && path[0] == 0 && options == AT_EMPTY_PATH && on()) {
        r = gsys(NR_statx, dirfd, (long) path, flags, mask, (long) stx);
        if (r == 0 && plain_fd(dirfd)) {
            fake_statx_ids(stx);
        } else {
            if (r != 0) refused(r);
            r = r == -EBADF ? r : FALLBACK;
        }
    } else if (options == 0) {
        char out[PATH_MAX];
        const char* base;
        struct kstat st;
        long dir = final(path, out, &base, &st, !(flags & AT_SYMLINK_NOFOLLOW));
        if (dir >= 0) {
            r = gsys(NR_statx, dir, (long) base, flags | AT_SYMLINK_NOFOLLOW, mask, (long) stx);
            close_parent(dir);
            if (r == 0) {
                fake_statx_ids(stx);
            } else {
                refused(r);
                r = FALLBACK;
            }
        } else {
            r = dir;
        }
    }
    if (r != FALLBACK) return (int) set_errno(r);
    return (int) set_errno(sys(NR_statx, dirfd, (long) path, flags, mask, (long) stx));
}

/* -- access ------------------------------------------------------------------------- */

static int do_faccessat(int dirfd, const char* path, int mode, int flags) {
    if (flags != 0) return (int) set_errno(sys(NR_faccessat2, dirfd, (long) path, mode, flags, 0));
    char out[PATH_MAX];
    const char* base;
    struct kstat st;
    long dir = final(path, out, &base, &st, 1);
    if (dir >= 0) {
        long r = mode == 0 /* F_OK */ ? 0 : gsys(NR_faccessat, dir, (long) base, mode, 0, 0);
        close_parent(dir);
        if (r == 0) return 0; /* refused: fake root may grant it */
    } else if (dir != FALLBACK) {
        return (int) set_errno(dir);
    }
    return (int) set_errno(sys(NR_faccessat, dirfd, (long) path, mode, 0, 0));
}

API int access(const char* path, int mode) { return do_faccessat(AT_FDCWD, path, mode, 0); }
API int faccessat(int dirfd, const char* path, int mode, int flags) { return do_faccessat(dirfd, path, mode, flags); }

/* -- readlink ------------------------------------------------------------------------ */

static long do_readlinkat(int dirfd, const char* path, char* buf, size_t size) {
    if (size > 0) {
        char out[PATH_MAX];
        const char* base;
        struct kstat st;
        long dir = final(path, out, &base, &st, 0);
        if (dir >= 0) {
            long r = gsys(NR_readlinkat, dir, (long) base, (long) buf, (long) size, 0);
            close_parent(dir);
            /* A full buffer may hold a truncated host path: proot rereads those. */
            if ((r >= 0 && r < (long) size) || r == -EINVAL) return set_errno(r);
            if (r < 0) refused(r);
        } else if (dir != FALLBACK) {
            return set_errno(dir);
        }
    }
    return set_errno(sys(NR_readlinkat, dirfd, (long) path, (long) buf, (long) size, 0));
}

API long readlink(const char* path, char* buf, size_t size) { return do_readlinkat(AT_FDCWD, path, buf, size); }
API long readlinkat(int dirfd, const char* path, char* buf, size_t size) {
    return do_readlinkat(dirfd, path, buf, size);
}
/* _FORTIFY_SOURCE: the buffer size is checked by the caller's compiler where it can. */
API long __readlink_chk(const char* path, char* buf, size_t size, size_t buflen) {
    return do_readlinkat(AT_FDCWD, path, buf, size < buflen ? size : buflen);
}
API long __readlinkat_chk(int dirfd, const char* path, char* buf, size_t size, size_t buflen) {
    return do_readlinkat(dirfd, path, buf, size < buflen ? size : buflen);
}

/* -- open --------------------------------------------------------------------------- */

static int do_openat(int dirfd, const char* path, int flags, unsigned mode) {
    if ((flags & O_ACCMODE) == O_RDONLY && (flags & (O_CREAT | O_TMPFILE_BIT)) == 0) {
        char out[PATH_MAX];
        const char* base;
        struct kstat st;
        long dir = final(path, out, &base, &st, !(flags & O_NOFOLLOW));
        if (dir >= 0) {
            long fd = gsys(NR_openat, dir, (long) base, flags | O_NOFOLLOW, 0, 0);
            close_parent(dir);
            if (fd >= 0 || fd == -ENOENT || fd == -ENOTDIR || fd == -EINTR || fd == -EMFILE || fd == -ENFILE ||
                fd == -ELOOP /* O_NOFOLLOW on a symlink */)
                return (int) set_errno(fd);
            refused(fd);
        } else if (dir != FALLBACK) {
            return (int) set_errno(dir);
        }
    }
    return (int) set_errno(sys(NR_openat, dirfd, (long) path, flags, mode, 0));
}

static unsigned open_mode(int flags, va_list ap) {
    return (flags & O_CREAT) || (flags & O_TMPFILE_BIT) ? va_arg(ap, unsigned) : 0;
}

API int open(const char* path, int flags, ...) {
    va_list ap;
    va_start(ap, flags);
    unsigned mode = open_mode(flags, ap);
    va_end(ap);
    return do_openat(AT_FDCWD, path, flags, mode);
}
API int open64(const char* path, int flags, ...) {
    va_list ap;
    va_start(ap, flags);
    unsigned mode = open_mode(flags, ap);
    va_end(ap);
    return do_openat(AT_FDCWD, path, flags, mode);
}
API int openat(int dirfd, const char* path, int flags, ...) {
    va_list ap;
    va_start(ap, flags);
    unsigned mode = open_mode(flags, ap);
    va_end(ap);
    return do_openat(dirfd, path, flags, mode);
}
API int openat64(int dirfd, const char* path, int flags, ...) {
    va_list ap;
    va_start(ap, flags);
    unsigned mode = open_mode(flags, ap);
    va_end(ap);
    return do_openat(dirfd, path, flags, mode);
}
/* _FORTIFY_SOURCE's, for opens without a mode. */
API int __open_2(const char* path, int flags) { return do_openat(AT_FDCWD, path, flags, 0); }
API int __open64_2(const char* path, int flags) { return do_openat(AT_FDCWD, path, flags, 0); }
API int __openat_2(int dirfd, const char* path, int flags) { return do_openat(dirfd, path, flags, 0); }
API int __openat64_2(int dirfd, const char* path, int flags) { return do_openat(dirfd, path, flags, 0); }

/* -- realpath ------------------------------------------------------------------------ */

typedef char* (*realpath_fn)(const char*, char*);

/* A path whose symlinks are resolved: plain ones are their own canonical form. */
API char* realpath(const char* path, char* resolved) {
    long last = plain_path(path);
    char out[PATH_MAX];
    const char* canonical = 0;
    if ((last >= 0 || (path != 0 && path[0] == '/')) && on()) {
        long r = SYMLINK;
        if (last >= 0) {
            struct open_how how = {O_PATH | O_CLOEXEC, 0, RESOLVE_IN_ROOT | RESOLVE_NO_SYMLINKS};
            r = gsys(NR_openat2, root_fd, (long) path, (long) &how, sizeof(how), 0);
            if (r >= 0) {
                gsys(NR_close, r, 0, 0, 0, 0);
                canonical = path;
            } else if (r == -ELOOP) {
                r = SYMLINK;
            }
        }
        if (r == SYMLINK) {
            r = resolve(path, 1, out);
            if (r == 0) canonical = out;
        }
        if (canonical == 0 && (r == -ENOENT || r == -ENOTDIR || r == -ELOOP)) {
            set_errno(r);
            return 0;
        }
        if (canonical == 0 && r != FALLBACK) refused(r);
    }
    if (canonical != 0) {
        long length = length_of(canonical);
        if (resolved == 0) resolved = malloc((size_t) length + 1);
        if (resolved == 0) return 0; /* malloc set ENOMEM */
        for (long i = 0; i <= length; i++) resolved[i] = canonical[i];
        return resolved;
    }
    realpath_fn next = (realpath_fn) dlsym(RTLD_NEXT, "realpath");
    return next(path, resolved);
}

API char* __realpath_chk(const char* path, char* resolved, size_t resolvedlen) {
    (void) resolvedlen; /* glibc aborts below PATH_MAX; callers pass PATH_MAX buffers */
    return realpath(path, resolved);
}

API char* canonicalize_file_name(const char* path) { return realpath(path, 0); }

/* -- Ids ----------------------------------------------------------------------------- */

/* fake_id0 reports the app's files as owned by the saved set ids: follow
 * them when the program changes its ids (apt drops to _apt). */
#define SET_ID(name, params, args)                          \
    API int name params {                                   \
        typedef int (*fn) params;                           \
        int result = ((fn) dlsym(RTLD_NEXT, #name)) args;   \
        if (__atomic_load_n(&state, __ATOMIC_ACQUIRE) == ON) \
            read_fake_ids();                                \
        return result;                                      \
    }

SET_ID(setuid, (unsigned uid), (uid))
SET_ID(setgid, (unsigned gid), (gid))
SET_ID(seteuid, (unsigned uid), (uid))
SET_ID(setegid, (unsigned gid), (gid))
SET_ID(setreuid, (unsigned r, unsigned e), (r, e))
SET_ID(setregid, (unsigned r, unsigned e), (r, e))
SET_ID(setresuid, (unsigned r, unsigned e, unsigned s), (r, e, s))
SET_ID(setresgid, (unsigned r, unsigned e, unsigned s), (r, e, s))
