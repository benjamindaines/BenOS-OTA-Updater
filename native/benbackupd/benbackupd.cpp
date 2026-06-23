// SPDX-License-Identifier: GPL-3.0-only
//
// benbackupd - minimal privileged read helper for the BenOS updater backup.
//
// It is the ONLY component that touches other apps' private data. It holds
// CAP_DAC_READ_SEARCH (to traverse/read 0700 dirs owned by other UIDs) and runs
// in an SELinux domain permitted to read app_data_file & friends. The updater
// app connects over a SOCK_SEQPACKET unix socket created by init and asks it to
// (L)ist a directory subtree or (R)ead a file's bytes.
//
// It deliberately does NOT do tar/compression/encryption: those stay in the app
// (reusing Neo-Backup's exact code) so the on-disk format is guaranteed
// compatible. This binary stays tiny and auditable.
//
// Protocol (little-endian), matched by PrivilegedFs.kt:
//   Request : u8 op, u16 pathLen, path
//   'L' rep : repeated records, each:
//               u8 type(1 reg,2 dir,3 lnk,4 fifo,0xFE err,0xFF end)
//               u32 mode(&07777) u32 uid u32 gid u64 size
//               i64 mtimeSec u32 mtimeNsec
//               u16 relLen rel  u16 linkLen link
//   'R' rep : u8 status(0 ok,1 err); if ok: u64 size; then size bytes streamed.
//
// Hardening: it only serves absolute paths under an allow-list of roots
// (/data/user, /data/user_de, /data/app, /storage/emulated). It refuses any
// path containing "/../". This bounds what a compromised client can read even
// though the client (the platform updater) is already trusted.

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <string>
#include <vector>

#include <android-base/file.h>
#include <android-base/logging.h>
#include <cutils/sockets.h>

namespace {

constexpr char kSocketName[] = "benbackupd";

const char* const kAllowedRoots[] = {
    "/data/user/",
    "/data/user_de/",
    "/data/data/",
    "/data/app/",
    "/storage/emulated/",
    "/mnt/expand/",   // adopted storage, if used
};

bool PathAllowed(const std::string& p) {
    if (p.empty() || p[0] != '/') return false;
    if (p.find("/../") != std::string::npos) return false;
    if (p.size() >= 3 && p.compare(p.size() - 3, 3, "/..") == 0) return false;
    for (const char* root : kAllowedRoots) {
        if (p.compare(0, strlen(root), root) == 0) return true;
    }
    return false;
}

// ---- framed writes --------------------------------------------------------

bool WriteAll(int fd, const void* buf, size_t len) {
    const uint8_t* p = static_cast<const uint8_t*>(buf);
    while (len > 0) {
        ssize_t n = TEMP_FAILURE_RETRY(write(fd, p, len));
        if (n <= 0) return false;
        p += n;
        len -= static_cast<size_t>(n);
    }
    return true;
}

template <typename T>
bool WriteScalar(std::vector<uint8_t>& out, T v) {
    const uint8_t* p = reinterpret_cast<const uint8_t*>(&v);
    out.insert(out.end(), p, p + sizeof(T));
    return true;
}

void AppendLenString(std::vector<uint8_t>& out, const std::string& s) {
    uint16_t len = static_cast<uint16_t>(s.size() > 0xFFFF ? 0xFFFF : s.size());
    WriteScalar<uint16_t>(out, len);
    out.insert(out.end(), s.begin(), s.begin() + len);
}

// ---- LIST -----------------------------------------------------------------

uint8_t TypeOf(const struct stat& st) {
    if (S_ISREG(st.st_mode)) return 1;
    if (S_ISDIR(st.st_mode)) return 2;
    if (S_ISLNK(st.st_mode)) return 3;
    if (S_ISFIFO(st.st_mode)) return 4;
    return 0;  // skip block/char/socket, like Neo-Backup
}

void EmitNode(int client, const std::string& rel, const struct stat& st,
              const std::string& link) {
    std::vector<uint8_t> rec;
    uint8_t type = TypeOf(st);
    if (type == 0) return;
    WriteScalar<uint8_t>(rec, type);
    WriteScalar<uint32_t>(rec, static_cast<uint32_t>(st.st_mode & 07777));
    WriteScalar<uint32_t>(rec, static_cast<uint32_t>(st.st_uid));
    WriteScalar<uint32_t>(rec, static_cast<uint32_t>(st.st_gid));
    WriteScalar<uint64_t>(rec, S_ISREG(st.st_mode) ? static_cast<uint64_t>(st.st_size) : 0);
    WriteScalar<int64_t>(rec, static_cast<int64_t>(st.st_mtim.tv_sec));
    WriteScalar<uint32_t>(rec, static_cast<uint32_t>(st.st_mtim.tv_nsec));
    AppendLenString(rec, rel);
    AppendLenString(rec, link);
    WriteAll(client, rec.data(), rec.size());
}

// Pre-order recursive walk (dir emitted before its children), matching the
// order Neo-Backup's tar writer expects.
void WalkDir(int client, const std::string& absRoot, const std::string& rel) {
    std::string abs = rel.empty() ? absRoot : absRoot + "/" + rel;
    DIR* d = opendir(abs.c_str());
    if (!d) return;
    std::vector<std::string> subdirs;
    struct dirent* de;
    while ((de = readdir(d)) != nullptr) {
        std::string name = de->d_name;
        if (name == "." || name == "..") continue;
        std::string childRel = rel.empty() ? name : rel + "/" + name;
        std::string childAbs = absRoot + "/" + childRel;

        struct stat st;
        if (lstat(childAbs.c_str(), &st) != 0) {
            // emit an error record so the client can log it, then continue
            std::vector<uint8_t> rec;
            WriteScalar<uint8_t>(rec, 0xFE);
            AppendLenString(rec, childRel);
            WriteAll(client, rec.data(), rec.size());
            continue;
        }
        std::string link;
        if (S_ISLNK(st.st_mode)) {
            char buf[4096];
            ssize_t n = readlink(childAbs.c_str(), buf, sizeof(buf) - 1);
            if (n >= 0) { buf[n] = '\0'; link = buf; }
        }
        EmitNode(client, childRel, st, link);
        if (S_ISDIR(st.st_mode)) subdirs.push_back(childRel);
    }
    closedir(d);
    for (const auto& s : subdirs) WalkDir(client, absRoot, s);
}

void HandleList(int client, const std::string& path) {
    if (PathAllowed(path)) {
        struct stat st;
        if (lstat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode)) {
            WalkDir(client, path, "");
        }
    }
    uint8_t end = 0xFF;
    WriteAll(client, &end, 1);
}

// ---- READ -----------------------------------------------------------------

void HandleRead(int client, const std::string& path) {
    if (!PathAllowed(path)) {
        uint8_t status = 1;
        WriteAll(client, &status, 1);
        return;
    }
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) {
        uint8_t status = 1;
        WriteAll(client, &status, 1);
        return;
    }
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode)) {
        close(fd);
        uint8_t status = 1;
        WriteAll(client, &status, 1);
        return;
    }
    uint8_t status = 0;
    WriteAll(client, &status, 1);
    uint64_t size = static_cast<uint64_t>(st.st_size);
    WriteAll(client, &size, sizeof(size));

    char buf[256 * 1024];
    uint64_t remaining = size;
    while (remaining > 0) {
        size_t want = remaining < sizeof(buf) ? static_cast<size_t>(remaining) : sizeof(buf);
        ssize_t n = TEMP_FAILURE_RETRY(read(fd, buf, want));
        if (n <= 0) break;  // truncated read; client detects via size mismatch
        if (!WriteAll(client, buf, static_cast<size_t>(n))) break;
        remaining -= static_cast<uint64_t>(n);
    }
    close(fd);
}

// ---- request loop ---------------------------------------------------------

void ServeClient(int client) {
    // One request per connection keeps framing trivial. The app opens a fresh
    // connection per list/read, which is cheap for a local socket.
    uint8_t hdr[3];
    ssize_t n = TEMP_FAILURE_RETRY(recv(client, hdr, sizeof(hdr), MSG_WAITALL));
    if (n != static_cast<ssize_t>(sizeof(hdr))) return;
    uint8_t op = hdr[0];
    uint16_t pathLen = static_cast<uint16_t>(hdr[1] | (hdr[2] << 8));
    std::string path(pathLen, '\0');
    if (pathLen > 0) {
        n = TEMP_FAILURE_RETRY(recv(client, &path[0], pathLen, MSG_WAITALL));
        if (n != pathLen) return;
    }
    switch (op) {
        case 'L': HandleList(client, path); break;
        case 'R': HandleRead(client, path); break;
        default: break;
    }
}

}  // namespace

int main() {
    // init passes us the listening socket via the environment (android_get_control_socket).
    int listen_fd = android_get_control_socket(kSocketName);
    if (listen_fd < 0) {
        LOG(ERROR) << "Failed to get control socket " << kSocketName;
        return 1;
    }
    if (listen(listen_fd, 8) < 0) {
        PLOG(ERROR) << "listen failed";
        return 1;
    }
    LOG(INFO) << "benbackupd ready";

    while (true) {
        int client = TEMP_FAILURE_RETRY(accept4(listen_fd, nullptr, nullptr, SOCK_CLOEXEC));
        if (client < 0) {
            if (errno == EINTR) continue;
            PLOG(ERROR) << "accept failed";
            continue;
        }
        ServeClient(client);
        close(client);
    }
}
