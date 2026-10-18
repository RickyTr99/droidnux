/*
 * SysV shared memory for MIT-SHM between the X server, which runs in the app,
 * and X clients in the container.
 *
 * Android has no SysV IPC. proot emulates it for the guest, but the X server
 * is outside proot and cannot see those segments, so every XShmAttach would
 * fail and clients would fall back to sending images over the socket. Here a
 * segment is a sealed memfd owned by the process that created it; its id
 * encodes an abstract socket on which that process hands the fd out to
 * processes of the same Android user.
 *
 * Built twice from this file:
 *   - DNX_SHM_SERVER: in the X server, as libandroid_shm* (the names
 *     Termux:X11's xserver sources are built against).
 *   - DNX_SHM_GUEST: libdroidnux-guest-shm.so, preloaded into the desktop
 *     session in the container. It is compiled with the NDK but loaded by
 *     glibc, so it makes syscalls itself, has its own copies of the kernel
 *     structures and calls only glibc functions whose ABI matches bionic's
 *     (pthread_create, __errno_location).
 *
 * In the guest, segments with a key (rare, never shared with the X server)
 * and ids this file does not know go to the real syscalls, i.e. to proot.
 */

#include <stddef.h>
#include <stdint.h>

#if !defined(__aarch64__)
#error "the syscall numbers and structures below are arm64's"
#endif

#if defined(DNX_SHM_GUEST)
#define SHM_API(name) __attribute__((visibility("default"))) name
extern int* __errno_location(void);
#define dnx_errno (*__errno_location())
#define PASS_TO_KERNEL 1
#elif defined(DNX_SHM_SERVER)
#define SHM_API(name) libandroid_##name
extern int* __errno(void);
#define dnx_errno (*__errno())
#define PASS_TO_KERNEL 0
#else
#error "define DNX_SHM_GUEST or DNX_SHM_SERVER"
#endif

/* Same signature in bionic and glibc (pthread_t is an unsigned long in both). */
extern int pthread_create(unsigned long* thread, const void* attr, void* (*start)(void*), void* arg);

/* -- Kernel interface (arm64) ----------------------------------------------- */

enum {
    NR_fcntl = 25, NR_ftruncate = 46, NR_close = 57, NR_read = 63, NR_write = 64, NR_fstat = 80,
    NR_nanosleep = 101, NR_sched_yield = 124, NR_getpid = 172, NR_shmget = 194, NR_shmctl = 195,
    NR_shmat = 196, NR_shmdt = 197, NR_socket = 198, NR_socketpair = 199, NR_bind = 200, NR_listen = 201,
    NR_connect = 203, NR_setsockopt = 208, NR_getsockopt = 209, NR_sendmsg = 211, NR_recvmsg = 212,
    NR_munmap = 215, NR_mmap = 222, NR_accept4 = 242, NR_memfd_create = 279,
};

enum {
    EINTR = 4, ENOMEM = 12, EFAULT = 14, EINVAL = 22, ENFILE = 23, EMFILE = 24, ECONNABORTED = 103,
    ENOBUFS = 105,
};

enum {
    AF_UNIX = 1, SOCK_STREAM = 1, SOCK_CLOEXEC = 0x80000, SOL_SOCKET = 1, SO_PEERCRED = 17,
    SO_RCVTIMEO = 20, SO_SNDTIMEO = 21, SCM_RIGHTS = 1, MSG_CMSG_CLOEXEC = 0x40000000,
    MFD_CLOEXEC = 1, MFD_ALLOW_SEALING = 2, F_ADD_SEALS = 1033, F_GET_SEALS = 1034,
    F_SEAL_SEAL = 1, F_SEAL_SHRINK = 2, F_SEAL_GROW = 4,
    PROT_READ = 1, PROT_WRITE = 2, PROT_EXEC = 4, MAP_SHARED = 1, MAP_FIXED = 0x10,
    MAP_FIXED_NOREPLACE = 0x100000,
};

enum {
    IPC_PRIVATE = 0, IPC_RMID = 0, IPC_SET = 1, IPC_STAT = 2, IPC_64 = 0x100,
    SHM_LOCK = 11, SHM_UNLOCK = 12, SHM_STAT = 13, SHM_STAT_ANY = 15,
    SHM_RDONLY = 010000, SHM_RND = 020000, SHM_REMAP = 040000, SHM_EXEC = 0100000,
    SHMLBA = 4096,
};

struct k_sockaddr_un { uint16_t family; char path[108]; };
struct k_iovec { void* base; size_t len; };
struct k_msghdr {
    void* name; int namelen; struct k_iovec* iov; size_t iovlen; void* control; size_t controllen; unsigned flags;
};
struct k_cmsg_fd { size_t len; int level; int type; int fd; int pad; };  /* CMSG_SPACE(sizeof(int)) */
#define CMSG_FD_LEN (sizeof(size_t) + 2 * sizeof(int) + sizeof(int))       /* CMSG_LEN(sizeof(int)) */
struct k_ucred { int pid; uint32_t uid; uint32_t gid; };
struct k_timespec { long sec; long nsec; };
struct k_stat {
    uint64_t dev, ino; uint32_t mode, nlink, uid, gid; uint64_t rdev, pad1; int64_t size;
    int32_t blksize, pad2; int64_t blocks; long times[6]; uint32_t unused[2];
};
/* struct shmid_ds of glibc on arm64 and struct shmid64_ds of the kernel. */
struct k_shmid_ds {
    struct {
        int32_t key; uint32_t uid, gid, cuid, cgid, mode; uint16_t seq, pad; unsigned long unused[2];
    } perm;
    size_t segsz; long atime, dtime, ctime; int32_t cpid, lpid; unsigned long nattch, unused[2];
};
_Static_assert(sizeof(struct k_msghdr) == 56, "msghdr");
_Static_assert(sizeof(struct k_cmsg_fd) == 24, "cmsghdr");
_Static_assert(sizeof(struct k_stat) == 128, "stat");
_Static_assert(sizeof(struct k_shmid_ds) == 112, "shmid_ds");

static long sys(long n, long a, long b, long c, long d, long e, long f) {
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x5 __asm__("x5") = f;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5) : "memory", "cc");
    return x0;
}
#define SYS0(n) sys(n, 0, 0, 0, 0, 0, 0)
#define SYS1(n, a) sys(n, (long) (a), 0, 0, 0, 0, 0)
#define SYS2(n, a, b) sys(n, (long) (a), (long) (b), 0, 0, 0, 0)
#define SYS3(n, a, b, c) sys(n, (long) (a), (long) (b), (long) (c), 0, 0, 0)
#define SYS4(n, a, b, c, d) sys(n, (long) (a), (long) (b), (long) (c), (long) (d), 0, 0)
#define SYS5(n, a, b, c, d, e) sys(n, (long) (a), (long) (b), (long) (c), (long) (d), (long) (e), 0)
#define SYS6(n, a, b, c, d, e, f) sys(n, (long) (a), (long) (b), (long) (c), (long) (d), (long) (e), (long) (f))

static int failed(long r) { return (unsigned long) r > -4096UL; }

/* Syscall result to the libc convention. */
static long result(long r) {
    if (failed(r)) {
        dnx_errno = (int) -r;
        return -1;
    }
    return r;
}

/* -- State ------------------------------------------------------------------- */

enum { MAX_SEGMENTS = 1024, MAX_ATTACHES = 1024 };

struct segment {
    int id;       /* 0: free slot */
    int fd;
    int key, mode, cpid;
    int nattch;   /* attaches in this process */
    uint64_t size;
    uint8_t local;    /* created here, served on our socket */
    uint8_t removed;  /* IPC_RMID seen: freed at the last detach */
};

struct attach {
    uintptr_t addr;  /* 0: free slot */
    size_t len;
    int segment;
};

static struct segment segments[MAX_SEGMENTS];
static struct attach attaches[MAX_ATTACHES];
static int lock_word;
static int owner_pid;
static int socket_id;  /* 15 bits, the high half of our ids; 0 until we listen */
static int listen_fd = -1;
static int counter;
static long real_uid = -1;

static void lock(void) {
    while (__atomic_exchange_n(&lock_word, 1, __ATOMIC_ACQUIRE)) SYS0(NR_sched_yield);
}

static void unlock(void) {
    __atomic_store_n(&lock_word, 0, __ATOMIC_RELEASE);
}

static void zero(void* p, size_t n) {
    for (size_t i = 0; i < n; i++) ((volatile char*) p)[i] = 0;
}

static int find_segment(int id) {
    if (id <= 0) return -1;
    for (int i = 0; i < MAX_SEGMENTS; i++) {
        if (segments[i].id == id) return i;
    }
    return -1;
}

static int free_segment_slot(void) {
    for (int i = 0; i < MAX_SEGMENTS; i++) {
        if (segments[i].id == 0) return i;
    }
    return -1;
}

static void release_if_unused(int i) {
    struct segment* s = &segments[i];
    if (s->nattch == 0 && (!s->local || s->removed)) {
        SYS1(NR_close, s->fd);
        zero(s, sizeof(*s));
    }
}

/*
 * After fork() the child has the parent's table but not its listener thread:
 * the parent keeps serving its segments, the child only keeps what it has
 * attached.
 */
static void check_fork(void) {
    int pid = (int) SYS0(NR_getpid);
    if (owner_pid == pid) return;
    if (owner_pid != 0) {
        lock_word = 0;
        if (listen_fd >= 0) SYS1(NR_close, listen_fd);
        listen_fd = -1;
        socket_id = 0;
        for (int i = 0; i < MAX_SEGMENTS; i++) {
            if (segments[i].id == 0) continue;
            segments[i].local = 0;
            release_if_unused(i);
        }
    }
    owner_pid = pid;
}

/* The kernel's uid for this process (getuid() is faked by proot). */
static long our_uid(void) {
    if (real_uid >= 0) return real_uid;
    int sv[2];
    if (failed(SYS4(NR_socketpair, AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, sv))) return -1;
    struct k_ucred cred;
    uint32_t len = sizeof(cred);
    long r = SYS5(NR_getsockopt, sv[0], SOL_SOCKET, SO_PEERCRED, &cred, &len);
    SYS1(NR_close, sv[0]);
    SYS1(NR_close, sv[1]);
    if (failed(r)) return -1;
    real_uid = cred.uid;
    return real_uid;
}

/* Abstract sockets are visible to every app: talk only to our own user. */
static int peer_is_us(int fd) {
    struct k_ucred cred;
    uint32_t len = sizeof(cred);
    if (failed(SYS5(NR_getsockopt, fd, SOL_SOCKET, SO_PEERCRED, &cred, &len))) return 0;
    long uid = our_uid();
    return uid >= 0 && cred.uid == (uint32_t) uid;
}

/* A stuck peer must not hang the X server. */
static void set_timeouts(int fd) {
    struct k_timespec tv = {1, 0};  /* struct timeval has the same layout on arm64 */
    SYS5(NR_setsockopt, fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    SYS5(NR_setsockopt, fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
}

static int socket_address(struct k_sockaddr_un* addr, int sid) {
    static const char prefix[] = "droidnux-shm-";
    static const char hex[] = "0123456789abcdef";
    zero(addr, sizeof(*addr));
    addr->family = AF_UNIX;
    int n = 1;  /* path[0] = 0: abstract namespace */
    for (const char* p = prefix; *p; p++) addr->path[n++] = *p;
    for (int shift = 12; shift >= 0; shift -= 4) addr->path[n++] = hex[(sid >> shift) & 15];
    return (int) sizeof(addr->family) + n;
}

/* -- Serving our segments ----------------------------------------------------- */

struct reply {
    int32_t found, key, mode, cpid;
    uint64_t size;
};

static void handle_request(int c) {
    if (!peer_is_us(c)) return;
    set_timeouts(c);
    int32_t id;
    if (SYS3(NR_read, c, &id, sizeof(id)) != sizeof(id)) return;

    struct reply reply;
    zero(&reply, sizeof(reply));
    struct k_cmsg_fd cmsg;
    zero(&cmsg, sizeof(cmsg));
    struct k_iovec iov = {&reply, sizeof(reply)};
    struct k_msghdr msg;
    zero(&msg, sizeof(msg));
    msg.iov = &iov;
    msg.iovlen = 1;

    lock();
    int i = find_segment(id);
    if (i >= 0 && segments[i].local) {
        struct segment* s = &segments[i];
        reply.found = 1;
        reply.key = s->key;
        reply.mode = s->mode;
        reply.cpid = s->cpid;
        reply.size = s->size;
        cmsg.len = CMSG_FD_LEN;
        cmsg.level = SOL_SOCKET;
        cmsg.type = SCM_RIGHTS;
        cmsg.fd = s->fd;
        msg.control = &cmsg;
        msg.controllen = sizeof(cmsg);
    }
    /* Under the lock, so the fd cannot be closed while it is being sent. */
    SYS3(NR_sendmsg, c, &msg, 0);
    unlock();
}

static void* serve(void* arg) {
    int fd = (int) (long) arg;
    for (;;) {
        long c = SYS4(NR_accept4, fd, 0, 0, SOCK_CLOEXEC);
        if (failed(c)) {
            if (c == -EINTR || c == -ECONNABORTED) continue;
            if (c == -EMFILE || c == -ENFILE || c == -ENOBUFS || c == -ENOMEM) {
                struct k_timespec pause = {0, 10 * 1000 * 1000};
                SYS2(NR_nanosleep, &pause, 0);
                continue;
            }
            return 0;
        }
        handle_request((int) c);
        SYS1(NR_close, c);
    }
}

/* Called with the lock held. */
static int ensure_listener(void) {
    if (listen_fd >= 0) return 0;
    if (our_uid() < 0) return -1;
    long fd = SYS3(NR_socket, AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (failed(fd)) return -1;
    for (int i = 0; i < 0x8000 && socket_id == 0; i++) {
        int sid = (owner_pid + i) & 0x7fff;
        if (sid == 0) continue;
        struct k_sockaddr_un addr;
        int len = socket_address(&addr, sid);
        if (!failed(SYS3(NR_bind, fd, &addr, len))) socket_id = sid;
    }
    unsigned long thread;
    if (socket_id == 0 || failed(SYS2(NR_listen, fd, 16)) ||
        pthread_create(&thread, 0, serve, (void*) fd) != 0) {
        SYS1(NR_close, fd);
        socket_id = 0;
        return -1;
    }
    listen_fd = (int) fd;
    counter = (owner_pid * 7919) & 0x7fff;  /* ids of a previous process with this socket id are unlikely */
    return 0;
}

/* -- Using segments of other processes ---------------------------------------- */

static int sealed_and_large_enough(int fd, uint64_t size) {
    /* A segment that can shrink could make the X server fault on its pages. */
    long seals = SYS2(NR_fcntl, fd, F_GET_SEALS);
    if (failed(seals) || !(seals & F_SEAL_SHRINK)) return 0;
    struct k_stat st;
    if (failed(SYS2(NR_fstat, fd, &st))) return 0;
    return st.size >= 0 && (uint64_t) st.size >= size;
}

/* Asks the owner of [id] for its segment; returns the table index or -1. */
static int fetch(int id) {
    int sid = (id >> 16) & 0x7fff;
    if (id <= 0 || sid == 0 || sid == socket_id) return -1;

    long c = SYS3(NR_socket, AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (failed(c)) return -1;
    set_timeouts((int) c);
    struct k_sockaddr_un addr;
    int len = socket_address(&addr, sid);
    int32_t request = id;
    int fd = -1;
    struct reply reply;
    zero(&reply, sizeof(reply));
    if (!failed(SYS3(NR_connect, c, &addr, len)) && peer_is_us((int) c) &&
        SYS3(NR_write, c, &request, sizeof(request)) == sizeof(request)) {
        struct k_cmsg_fd cmsg;
        zero(&cmsg, sizeof(cmsg));
        cmsg.fd = -1;
        struct k_iovec iov = {&reply, sizeof(reply)};
        struct k_msghdr msg;
        zero(&msg, sizeof(msg));
        msg.iov = &iov;
        msg.iovlen = 1;
        msg.control = &cmsg;
        msg.controllen = sizeof(cmsg);
        long n = SYS3(NR_recvmsg, c, &msg, MSG_CMSG_CLOEXEC);
        if (msg.controllen >= CMSG_FD_LEN && cmsg.level == SOL_SOCKET && cmsg.type == SCM_RIGHTS) fd = cmsg.fd;
        if (n != sizeof(reply) || !reply.found) {
            if (fd >= 0) SYS1(NR_close, fd);
            fd = -1;
        }
    }
    SYS1(NR_close, c);
    if (fd < 0) return -1;
    if (!sealed_and_large_enough(fd, reply.size)) {
        SYS1(NR_close, fd);
        return -1;
    }

    lock();
    int i = find_segment(id);  /* another thread may have fetched it meanwhile */
    if (i < 0 && (i = free_segment_slot()) >= 0) {
        struct segment* s = &segments[i];
        s->id = id;
        s->fd = fd;
        s->key = reply.key;
        s->mode = reply.mode;
        s->cpid = reply.cpid;
        s->size = reply.size;
        fd = -1;
    }
    unlock();
    if (fd >= 0) SYS1(NR_close, fd);
    return i;
}

/* -- API ---------------------------------------------------------------------- */

static int create(size_t size, int mode) {
    long fd = SYS2(NR_memfd_create, "droidnux-shm", MFD_CLOEXEC | MFD_ALLOW_SEALING);
    if (failed(fd)) return -1;
    if (failed(SYS2(NR_ftruncate, fd, size)) ||
        failed(SYS3(NR_fcntl, fd, F_ADD_SEALS, F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_SEAL))) {
        SYS1(NR_close, fd);
        return -1;
    }
    lock();
    int i = ensure_listener() == 0 ? free_segment_slot() : -1;
    int id = -1;
    if (i >= 0) {
        for (int tries = 0; tries < 0x7fff; tries++) {
            counter = counter % 0x7fff + 1;
            int candidate = socket_id << 16 | counter;
            if (find_segment(candidate) < 0) {
                id = candidate;
                break;
            }
        }
    }
    if (id > 0) {
        struct segment* s = &segments[i];
        s->id = id;
        s->fd = (int) fd;
        s->key = IPC_PRIVATE;
        s->mode = mode;
        s->cpid = owner_pid;
        s->size = size;
        s->local = 1;
    }
    unlock();
    if (id < 0) SYS1(NR_close, fd);
    return id;
}

int SHM_API(shmget)(int key, size_t size, int flags) {
    check_fork();
    if (key == IPC_PRIVATE && size > 0) {
        int id = create(size, flags & 0777);
        if (id > 0) return id;
    }
    if (PASS_TO_KERNEL) return (int) result(SYS3(NR_shmget, key, size, flags));
    dnx_errno = key == IPC_PRIVATE && size > 0 ? ENOMEM : EINVAL;
    return -1;
}

void* SHM_API(shmat)(int id, const void* address, int flags) {
    check_fork();
    lock();
    int known = find_segment(id) >= 0;
    unlock();
    if (!known) fetch(id);

    lock();
    int i = find_segment(id);
    if (i < 0) {
        unlock();
        if (PASS_TO_KERNEL) return (void*) result(SYS3(NR_shmat, id, address, flags));
        dnx_errno = EINVAL;
        return (void*) -1;
    }
    struct segment* s = &segments[i];
    int prot = PROT_READ | (flags & SHM_RDONLY ? 0 : PROT_WRITE) | (flags & SHM_EXEC ? PROT_EXEC : 0);
    int map = MAP_SHARED;
    uintptr_t addr = (uintptr_t) address;
    if (addr) {
        if (flags & SHM_RND) addr &= ~(uintptr_t) (SHMLBA - 1);
        map |= flags & SHM_REMAP ? MAP_FIXED : MAP_FIXED_NOREPLACE;
    }
    long r = SYS6(NR_mmap, addr, s->size, prot, map, s->fd, 0);
    int slot = -1;
    for (int a = 0; a < MAX_ATTACHES && !failed(r); a++) {
        if (attaches[a].addr == 0) {
            slot = a;
            break;
        }
    }
    if (!failed(r) && slot < 0) {
        SYS2(NR_munmap, r, s->size);
        r = -ENOMEM;
    }
    if (failed(r)) {
        release_if_unused(i);
        unlock();
        return (void*) result(r == -ENOMEM ? r : -EINVAL);
    }
    attaches[slot].addr = (uintptr_t) r;
    attaches[slot].len = s->size;
    attaches[slot].segment = i;
    s->nattch++;
    unlock();
    return (void*) r;
}

int SHM_API(shmdt)(const void* address) {
    check_fork();
    lock();
    for (int a = 0; a < MAX_ATTACHES; a++) {
        if (attaches[a].addr == 0 || attaches[a].addr != (uintptr_t) address) continue;
        SYS2(NR_munmap, attaches[a].addr, attaches[a].len);
        int i = attaches[a].segment;
        zero(&attaches[a], sizeof(attaches[a]));
        segments[i].nattch--;
        release_if_unused(i);
        unlock();
        return 0;
    }
    unlock();
    if (PASS_TO_KERNEL) return (int) result(SYS1(NR_shmdt, address));
    dnx_errno = EINVAL;
    return -1;
}

int SHM_API(shmctl)(int id, int cmd, void* buf) {
    check_fork();
    lock();
    int i = find_segment(id);
    if (i < 0) {
        unlock();
        if (PASS_TO_KERNEL) return (int) result(SYS3(NR_shmctl, id, cmd, buf));
        dnx_errno = EINVAL;
        return -1;
    }
    struct segment* s = &segments[i];
    long r = 0;
    switch (cmd & ~IPC_64) {
    case IPC_RMID:
        s->removed = 1;
        release_if_unused(i);
        break;
    case IPC_STAT:
    case SHM_STAT:
    case SHM_STAT_ANY: {
        if (!buf) {
            r = -EFAULT;
            break;
        }
        struct k_shmid_ds* ds = buf;
        zero(ds, sizeof(*ds));
        long uid = our_uid();
        ds->perm.key = s->key;
        ds->perm.uid = ds->perm.cuid = ds->perm.gid = ds->perm.cgid = (uint32_t) uid;
        ds->perm.mode = (uint32_t) s->mode;
        ds->segsz = s->size;
        ds->cpid = s->cpid;
        /* The owner keeps its own attach, unless it is us. */
        ds->nattch = (unsigned long) s->nattch + (s->local ? 0 : 1);
        if ((cmd & ~IPC_64) != IPC_STAT) r = id;
        break;
    }
    case IPC_SET:
    case SHM_LOCK:
    case SHM_UNLOCK:
        break;
    default:
        r = -EINVAL;
    }
    unlock();
    return (int) result(r);
}
