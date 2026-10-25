/*
 * Input method for the nested KWin (kwinrc [Wayland] InputMethod, written by
 * DesktopEnvironment). KWin starts it with WAYLAND_SOCKET, the private
 * connection of its input method; this program hands that socket to the
 * app's Wayland compositor (display/src/main/cpp/wl/nested.c), which is the
 * input method (Android's keyboard), and exits.
 *
 * A freestanding static binary: no libc, raw system calls, so it runs in the
 * glibc guest although it is built with the NDK (as libdroidnux-guest-im.so,
 * display/src/main/cpp/CMakeLists.txt).
 */
#include <stddef.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>

#define SOCKET_PATH "/tmp/.droidnux-im" /* the compositor's, see nested.c */

static long sys3(long number, long a, long b, long c) {
    register long x8 __asm__("x8") = number;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2) : "memory");
    return x0;
}

static __attribute__((noreturn)) void exit_group(int status) {
    sys3(__NR_exit_group, status, 0, 0);
    __builtin_unreachable();
}

/* The value of WAYLAND_SOCKET in `envp`, or -1. */
static int wayland_socket(char** envp) {
    static const char name[] = "WAYLAND_SOCKET=";
    for (; *envp; envp++) {
        const char* s = *envp;
        size_t i = 0;
        while (name[i] && s[i] == name[i]) i++;
        if (name[i]) continue;
        int fd = 0;
        for (s += i; *s >= '0' && *s <= '9'; s++) fd = fd * 10 + (*s - '0');
        return *s == '\0' && s != *envp + i ? fd : -1;
    }
    return -1;
}

__attribute__((used, noreturn)) void dnx_im_main(long* sp) {
    long argc = sp[0];
    char** envp = (char**) (sp + 1 + argc + 1);
    int fd = wayland_socket(envp);
    if (fd < 0) exit_group(2);

    int sock = (int) sys3(__NR_socket, AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (sock < 0) exit_group(3);
    struct sockaddr_un addr;
    addr.sun_family = AF_UNIX;
    size_t n = 0;
    for (; SOCKET_PATH[n]; n++) addr.sun_path[n] = SOCKET_PATH[n];
    addr.sun_path[n] = '\0';
    if (sys3(__NR_connect, sock, (long) &addr, (long) (offsetof(struct sockaddr_un, sun_path) + n + 1)) != 0)
        exit_group(4);

    char byte = 'i';
    struct iovec iov = {.iov_base = &byte, .iov_len = 1};
    union {
        char buf[CMSG_SPACE(sizeof(int))];
        struct cmsghdr align;
    } control;
    struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1, .msg_control = control.buf,
                         .msg_controllen = sizeof(control.buf)};
    struct cmsghdr* cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    *(int*) CMSG_DATA(cmsg) = fd;
    /* KWin restarts its input method only after a crash: a clean exit leaves the connection to the app. */
    exit_group(sys3(__NR_sendmsg, sock, (long) &msg, 0) == 1 ? 0 : 5);
}

__attribute__((naked, noreturn)) void _start(void) {
    __asm__("mov x0, sp\n"
            "bl dnx_im_main\n");
}
