// JNI UCI bridge for libstockfish.so.
//
// Stockfish's UCI protocol implementation (Stockfish::UCIEngine::loop(), in
// vendor/Stockfish/src/uci.cpp) reads commands from std::cin and writes
// replies to std::cout — it has no notion of Android or JNI. Rather than
// reimplementing UCI plumbing, this bridge creates two pipes, dup2()s them
// onto the process's STDIN_FILENO/STDOUT_FILENO, and runs the real
// UCIEngine::loop() (on a detached pthread) against those redirected fds.
// Java/Kotlin talks to the engine by writing/reading lines on the other end
// of each pipe via nativeWriteLine()/nativeReadLine().
//
// The init sequence in engine_thread_main() mirrors vendor/Stockfish/src/
// main.cpp exactly (Attacks::init(); Position::init(); construct a
// CommandLine and UCIEngine; Tune::init(...); uci.loop();) — main.cpp itself
// is excluded from this build (see CMakeLists.txt) specifically so this
// bridge can own process entry instead of a real `main()`.

#include <jni.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unistd.h>

#include "attacks.h"
#include "misc.h"
#include "position.h"
#include "tune.h"
#include "uci.h"

namespace {

// Pipe fds. stdinWriteFd/stdoutReadFd are the ends Kotlin talks to;
// STDIN_FILENO/STDOUT_FILENO are dup2'd onto the other ends so Stockfish's
// std::cin/std::cout transparently read/write through the pipes.
int g_stdinWriteFd = -1;
int g_stdoutReadFd = -1;

pthread_t g_engineThread;
std::atomic<bool> g_running{false};

std::mutex g_writeMutex;

std::mutex g_readMutex;
std::string g_readBuffer;

void close_if_open(int& fd) {
    if (fd >= 0) {
        close(fd);
        fd = -1;
    }
}

void* engine_thread_main(void* /*arg*/) {
    using namespace Stockfish;

    // Mirrors main.cpp's pre-loop sequence exactly.
    Attacks::init();
    Position::init();

    static char argv0[] = "stockfish";
    char* argv[]         = {argv0, nullptr};
    CommandLine cli(1, argv);
    UCIEngine uci(std::move(cli));

    Tune::init(uci.engine_options());

    uci.loop();  // Blocks here, reading redirected stdin, until "quit".

    return nullptr;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_net_palaya_chessanalyzer_engine_NativeBridge_nativeInit(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_running.exchange(true)) {
        return;  // Already initialized.
    }

    int stdinPipe[2];   // [0] read (-> STDIN_FILENO), [1] write (Kotlin writes)
    int stdoutPipe[2];  // [0] read (Kotlin reads), [1] write (-> STDOUT_FILENO)

    if (pipe(stdinPipe) != 0 || pipe(stdoutPipe) != 0) {
        g_running.store(false);
        return;
    }

    dup2(stdinPipe[0], STDIN_FILENO);
    dup2(stdoutPipe[1], STDOUT_FILENO);

    // The originals are now redundant with fd 0 / fd 1; only the pipe ends
    // Kotlin will actually use need to survive.
    close(stdinPipe[0]);
    close(stdoutPipe[1]);

    g_stdinWriteFd = stdinPipe[1];
    g_stdoutReadFd = stdoutPipe[0];

    // Line-buffer stdout so UCI replies (e.g. "bestmove ...") reach the pipe
    // promptly instead of sitting in a fully-buffered libc stdio buffer —
    // relevant because a pipe, unlike a tty, defaults to full buffering.
    setvbuf(stdout, nullptr, _IOLBF, 0);

    {
        std::lock_guard<std::mutex> lock(g_readMutex);
        g_readBuffer.clear();
    }

    pthread_create(&g_engineThread, nullptr, engine_thread_main, nullptr);
    pthread_detach(g_engineThread);
}

JNIEXPORT void JNICALL
Java_net_palaya_chessanalyzer_engine_NativeBridge_nativeWriteLine(JNIEnv* env, jobject /*thiz*/,
                                                                    jstring line) {
    if (!g_running.load() || g_stdinWriteFd < 0 || line == nullptr) {
        return;
    }

    const char* chars = env->GetStringUTFChars(line, nullptr);
    if (chars == nullptr) {
        return;
    }
    std::string s(chars);
    env->ReleaseStringUTFChars(line, chars);
    s.push_back('\n');

    std::lock_guard<std::mutex> lock(g_writeMutex);
    size_t off = 0;
    while (off < s.size()) {
        ssize_t n = write(g_stdinWriteFd, s.data() + off, s.size() - off);
        if (n <= 0) {
            break;  // Write end broken (engine shut down / pipe closed).
        }
        off += static_cast<size_t>(n);
    }
}

// Blocking read of one newline-terminated line from the engine's stdout.
// Returns null on EOF/shutdown (mirrors nativeReadLine(): String? in Kotlin).
JNIEXPORT jstring JNICALL
Java_net_palaya_chessanalyzer_engine_NativeBridge_nativeReadLine(JNIEnv* env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_readMutex);

    for (;;) {
        size_t nl = g_readBuffer.find('\n');
        if (nl != std::string::npos) {
            std::string line = g_readBuffer.substr(0, nl);
            if (!line.empty() && line.back() == '\r') {
                line.pop_back();
            }
            g_readBuffer.erase(0, nl + 1);
            return env->NewStringUTF(line.c_str());
        }

        if (g_stdoutReadFd < 0) {
            return nullptr;
        }

        char buf[4096];
        ssize_t n = read(g_stdoutReadFd, buf, sizeof(buf));
        if (n <= 0) {
            return nullptr;  // EOF or the pipe was closed by nativeShutdown().
        }
        g_readBuffer.append(buf, static_cast<size_t>(n));
    }
}

JNIEXPORT void JNICALL
Java_net_palaya_chessanalyzer_engine_NativeBridge_nativeShutdown(JNIEnv* /*env*/,
                                                                   jobject /*thiz*/) {
    if (!g_running.exchange(false)) {
        return;  // Already shut down (or never started).
    }

    {
        std::lock_guard<std::mutex> lock(g_writeMutex);
        if (g_stdinWriteFd >= 0) {
            const char quit[] = "quit\n";
            write(g_stdinWriteFd, quit, sizeof(quit) - 1);
        }
    }

    // Closing both ends unblocks the engine thread's std::cin read (EOF)
    // and any in-flight/future nativeReadLine() call.
    close_if_open(g_stdinWriteFd);
    close_if_open(g_stdoutReadFd);
}

}  // extern "C"
