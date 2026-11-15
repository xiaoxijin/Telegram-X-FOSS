package polynet

import (
	"context"
	"errors"
	"net"
	"sync"

	"github.com/xtls/xray-core/core"
)

// udpRelay carries the call engine's UDP (jni/voip/fork): each datagram arrives with an RFC 1928 UDP
// header naming its real destination, and replies go back with the real source in the same header.
// Xray's SOCKS5 inbound wants a TCP-held UDP ASSOCIATE per socket, too heavy for the engine's many sockets.
type udpRelay struct {
	instance *core.Instance
	conn     *net.UDPConn
	mu       sync.Mutex
	sessions map[string]net.PacketConn // per local socket; nil once closed
}

var relays = map[int64]*udpRelay{} // guarded by mu

// StartUDPRelay serves the call engine's UDP through instance id on 127.0.0.1:port; Stop(id) ends it.
func StartUDPRelay(id int64, port int) error {
	mu.Lock()
	instance := instances[id]
	mu.Unlock()
	if instance == nil {
		return errors.New("no such instance")
	}
	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port})
	if err != nil {
		return err
	}
	r := &udpRelay{instance: instance, conn: conn, sessions: map[string]net.PacketConn{}}
	mu.Lock()
	if instances[id] != instance || relays[id] != nil {
		mu.Unlock()
		conn.Close()
		return errors.New("instance stopped or relay already running")
	}
	relays[id] = r
	mu.Unlock()
	go r.serve()
	return nil
}

func (r *udpRelay) serve() {
	in := make([]byte, 65535)
	for {
		n, from, err := r.conn.ReadFromUDP(in)
		if err != nil {
			return // closed by Stop
		}
		if dest, payload := parseUDPHeader(in[:n]); dest != nil {
			if session := r.session(from); session != nil {
				session.WriteTo(payload, dest) // copies before returning
			}
		}
	}
}

func (r *udpRelay) session(from *net.UDPAddr) net.PacketConn {
	key := from.String()
	r.mu.Lock()
	defer r.mu.Unlock()
	if s := r.sessions[key]; s != nil || r.sessions == nil {
		return s
	}
	// supported third-party entry point; one session sends to any destination (XUDP, full cone)
	s, err := core.DialUDP(context.Background(), r.instance)
	if err != nil {
		return nil
	}
	r.sessions[key] = s
	go r.pump(key, from, s)
	return s
}

// pump copies replies to the local socket until the session ends (Xray ends it after a minute idle).
// ponytail: Xray's session queues 16 replies; use udp.NewDispatcher with a direct callback if video bursts drop.
func (r *udpRelay) pump(key string, to *net.UDPAddr, s net.PacketConn) {
	in := make([]byte, 65535)
	var out []byte
	for {
		n, src, err := s.ReadFrom(in)
		if err != nil {
			break
		}
		if a, ok := src.(*net.UDPAddr); ok && a.IP != nil {
			out = append(appendUDPHeader(out[:0], a), in[:n]...)
			r.conn.WriteToUDP(out, to)
		}
	}
	r.mu.Lock()
	if r.sessions[key] == s {
		delete(r.sessions, key)
	}
	r.mu.Unlock()
	s.Close()
}

func (r *udpRelay) close() {
	r.conn.Close()
	r.mu.Lock()
	sessions := r.sessions
	r.sessions = nil
	r.mu.Unlock()
	for _, s := range sessions {
		s.Close()
	}
}

// parseUDPHeader splits an RFC 1928 UDP datagram; nil for fragments, domain names and garbage.
func parseUDPHeader(p []byte) (*net.UDPAddr, []byte) {
	if len(p) < 4 || p[0]|p[1]|p[2] != 0 {
		return nil, nil
	}
	var ipLen int
	switch p[3] {
	case 1:
		ipLen = 4
	case 4:
		ipLen = 16
	default:
		return nil, nil // domain names: the engine only sends IPs
	}
	end := 4 + ipLen + 2
	if len(p) < end {
		return nil, nil
	}
	ip := append(net.IP(nil), p[4:4+ipLen]...)
	return &net.UDPAddr{IP: ip, Port: int(p[4+ipLen])<<8 | int(p[5+ipLen])}, p[end:]
}

func appendUDPHeader(b []byte, a *net.UDPAddr) []byte {
	if ip4 := a.IP.To4(); ip4 != nil {
		b = append(append(b, 0, 0, 0, 1), ip4...)
	} else {
		b = append(append(b, 0, 0, 0, 4), a.IP.To16()...)
	}
	return append(b, byte(a.Port>>8), byte(a.Port))
}
