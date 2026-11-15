// TG PRO fork (Tools/CALL_PROXY.md): carries the call engines' UDP over the built-in proxy.
//
// rtc::CreateDefaultSocketServer() wraps its PhysicalSocketServer with CallProxySocketServer
// (the call-proxy hook in webrtc/rtc_base/internal/default_socket_server.cc), and every tgcalls engine
// and group call takes its sockets from there. While PolyNodeTransport runs polynet's UDP relay
// (CallProxy.setRelayPort), UDP to public IPv4 goes to 127.0.0.1:relay with an RFC 1928 UDP header naming
// the real destination, and replies arrive with the real source in that header (jni/polynet/udprelay.go).
// Meanwhile public IPv6 UDP and public TCP fail rather than leak past the proxy: Telegram relays all have
// IPv4, and UDP to loopback is never blocked, so TCP relays aren't needed. Relay port 0 = upstream behaviour.

#include <android/log.h>
#include <jni.h>
#include <netinet/in.h>
#include <sys/socket.h>

#include <atomic>
#include <cerrno>
#include <cstring>
#include <memory>

#include "rtc_base/async_socket.h"
#include "rtc_base/buffer.h"
#include "rtc_base/ip_address.h"
#include "rtc_base/socket_address.h"
#include "rtc_base/socket_server.h"

#define CALL_PROXY_LOG(...) __android_log_print(ANDROID_LOG_INFO, "CallProxy", __VA_ARGS__)

namespace tgfork {
namespace {

std::atomic<int> relayPort{0};

constexpr int kHeaderSize = 10;  // RSV(2) FRAG(1) ATYP=1(1) IPv4(4) PORT(2)

bool IsPublic(const rtc::SocketAddress &address) {
    const rtc::IPAddress &ip = address.ipaddr();
    if (rtc::IPIsUnspec(ip) || rtc::IPIsAny(ip) || rtc::IPIsPrivate(ip)) {
        return false;  // LAN peers, loopback and unresolved names stay direct
    }
    return ip.family() != AF_INET || ntohl(ip.ipv4_address().s_addr) < 0xE0000000;  // not multicast/reserved
}

class ProxySocket : public rtc::AsyncSocketAdapter {
public:
    ProxySocket(rtc::Socket *socket, bool udp) : rtc::AsyncSocketAdapter(socket), udp_(udp) {
    }

    ~ProxySocket() override {
        if (sent_ != 0 || received_ != 0 || dropped_ != 0) {
            CALL_PROXY_LOG("udp socket closed: %u sent, %u received, %u dropped", sent_, received_, dropped_);
        }
    }

    int Connect(const rtc::SocketAddress &address) override {
        if (!udp_ && relayPort.load(std::memory_order_relaxed) != 0 && IsPublic(address)) {
            SetError(EHOSTUNREACH);  // no TCP relay: fail instead of going around the proxy
            return -1;
        }
        // connected UDP passes: BasicNetworkManager connects one to 8.8.8.8 every 2 s only to read its local IP
        return rtc::AsyncSocketAdapter::Connect(address);
    }

    int Send(const void *data, size_t size) override {
        if (udp_ && !sentDirect_ && relayPort.load(std::memory_order_relaxed) != 0 && IsPublic(GetRemoteAddress())) {
            sentDirect_ = true;
            CALL_PROXY_LOG("connected udp socket sends directly");  // the engines use SendTo; new engine code if seen
        }
        return rtc::AsyncSocketAdapter::Send(data, size);
    }

    int SendTo(const void *data, size_t size, const rtc::SocketAddress &address) override {
        const int port = relayPort.load(std::memory_order_relaxed);
        if (!udp_ || port == 0 || !IsPublic(address)) {
            return rtc::AsyncSocketAdapter::SendTo(data, size, address);
        }
        if (address.family() != AF_INET) {
            dropped_++;
            SetError(EHOSTUNREACH);  // the relay is on IPv4 loopback, and Telegram relays all have IPv4
            return -1;
        }
        uint8_t header[kHeaderSize] = {0, 0, 0, 1};
        const in_addr ip = address.ipaddr().ipv4_address();
        memcpy(header + 4, &ip.s_addr, 4);
        header[8] = static_cast<uint8_t>(address.port() >> 8);
        header[9] = static_cast<uint8_t>(address.port());
        packet_.SetData(header, kHeaderSize);
        packet_.AppendData(static_cast<const uint8_t *>(data), size);
        const int result = rtc::AsyncSocketAdapter::SendTo(packet_.data(), packet_.size(),
                                                           rtc::SocketAddress(rtc::IPAddress(INADDR_LOOPBACK), port));
        if (result < 0) {
            return result;
        }
        if (sent_++ == 0) {
            CALL_PROXY_LOG("udp socket relaying");
        }
        return static_cast<int>(size);  // the caller's count: RtpTransport reads any other value as a failed send
    }

    // AsyncUDPSocket reads with this overload
    int RecvFrom(ReceiveBuffer &buffer) override {
        const int length = GetSocket()->RecvFrom(buffer);
        if (length <= 0 || !FromRelay(buffer.source_address)) {
            return length;
        }
        const int payload = Unwrap(buffer.payload.data(), length, &buffer.source_address);
        buffer.payload.SetSize(payload > 0 ? payload : 0);
        return payload;
    }

    int RecvFrom(void *data, size_t size, rtc::SocketAddress *address, int64_t *timestamp) override {
        const int length = rtc::AsyncSocketAdapter::RecvFrom(data, size, address, timestamp);
        if (length <= 0 || address == nullptr || !FromRelay(*address)) {
            return length;
        }
        return Unwrap(static_cast<uint8_t *>(data), length, address);
    }

private:
    bool FromRelay(const rtc::SocketAddress &source) const {
        const int port = relayPort.load(std::memory_order_relaxed);
        return udp_ && port != 0 && source.port() == port && rtc::IPIsLoopback(source.ipaddr());
    }

    // Strips the header in place and reports the real source; -1 (dropped) for anything malformed.
    int Unwrap(uint8_t *data, int length, rtc::SocketAddress *source) {
        if (length < kHeaderSize || (data[0] | data[1] | data[2]) != 0 || data[3] != 1) {
            dropped_++;
            SetError(EWOULDBLOCK);
            return -1;
        }
        in_addr ip;
        memcpy(&ip.s_addr, data + 4, 4);
        *source = rtc::SocketAddress(rtc::IPAddress(ip), (data[8] << 8) | data[9]);
        const int payload = length - kHeaderSize;
        memmove(data, data + kHeaderSize, payload);
        received_++;
        return payload;
    }

    const bool udp_;
    bool sentDirect_ = false;
    rtc::Buffer packet_;
    unsigned sent_ = 0;
    unsigned received_ = 0;
    unsigned dropped_ = 0;
};

class CallProxySocketServer : public rtc::SocketServer {
public:
    explicit CallProxySocketServer(std::unique_ptr<rtc::SocketServer> inner) : inner_(std::move(inner)) {
    }

    rtc::Socket *CreateSocket(int family, int type) override {
        // the network monitor sets its binder on this server, but sockets bind through the inner one
        inner_->set_network_binder(network_binder());
        rtc::Socket *socket = inner_->CreateSocket(family, type);
        return socket != nullptr ? new ProxySocket(socket, type == SOCK_DGRAM) : nullptr;
    }

    void SetMessageQueue(rtc::Thread *queue) override {
        inner_->SetMessageQueue(queue);
    }

    bool Wait(webrtc::TimeDelta max_wait_duration, bool process_io) override {
        return inner_->Wait(max_wait_duration, process_io);
    }

    void WakeUp() override {
        inner_->WakeUp();
    }

private:
    const std::unique_ptr<rtc::SocketServer> inner_;
};

}  // namespace

std::unique_ptr<rtc::SocketServer> WrapCallProxy(std::unique_ptr<rtc::SocketServer> inner) {
    return std::make_unique<CallProxySocketServer>(std::move(inner));
}

}  // namespace tgfork

extern "C" JNIEXPORT void JNICALL Java_org_thunderdog_challegram_fork_CallProxy_setRelayPort(JNIEnv *, jclass, jint port) {
    tgfork::relayPort.store(port, std::memory_order_relaxed);
    CALL_PROXY_LOG("relay port %d", port);
}
