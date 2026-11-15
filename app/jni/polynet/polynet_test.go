package polynet

import (
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"golang.org/x/net/proxy"
)

const uuid = "27848739-7e62-4138-9fd3-098a63964b6b"

func freePort(t *testing.T) int {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}

// A local VLESS server plus a client shaped like PolyNodeConfig.toConfigJson(), both in this process.
// Returns the client's SOCKS5 port and handle; the server stops with the test.
func startVlessPair(t *testing.T) (int, int64) {
	serverPort, socksPort := freePort(t), freePort(t)
	server, err := Start(fmt.Sprintf(`{"log":{"loglevel":"warning"},
		"inbounds":[{"listen":"127.0.0.1","port":%d,"protocol":"vless","settings":{"clients":[{"id":%q}],"decryption":"none"},"streamSettings":{"network":"tcp","security":"none"}}],
		"outbounds":[{"protocol":"freedom","settings":{"finalRules":[{"action":"allow","ip":["127.0.0.1"]}]}}]}`, serverPort, uuid))
	// (freedom refuses private targets for vless clients by default; the test web server is on loopback)
	if err != nil {
		t.Fatal("server:", err)
	}
	t.Cleanup(func() { Stop(server) })
	client, err := Start(fmt.Sprintf(`{"log":{"loglevel":"warning"},
		"inbounds":[{"listen":"127.0.0.1","port":%d,"protocol":"socks","settings":{"auth":"noauth","udp":true}}],
		"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"127.0.0.1","port":%d,"users":[{"id":%q,"encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"none"}}]}`, socksPort, serverPort, uuid))
	if err != nil {
		t.Fatal("client:", err)
	}
	t.Cleanup(func() { Stop(client) })
	return socksPort, client
}

// An HTTP request through the client's SOCKS5 inbound must reach a local web server.
func TestRelaysThroughVlessAndStops(t *testing.T) {
	web := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { io.WriteString(w, "pong") }))
	defer web.Close()
	socksPort, client := startVlessPair(t)

	dialer, err := proxy.SOCKS5("tcp", fmt.Sprintf("127.0.0.1:%d", socksPort), nil, proxy.Direct)
	if err != nil {
		t.Fatal(err)
	}
	httpClient := &http.Client{Timeout: 10 * time.Second, Transport: &http.Transport{Dial: dialer.Dial}}
	res, err := httpClient.Get(web.URL)
	if err != nil {
		t.Fatal("request through the proxy:", err)
	}
	body, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if string(body) != "pong" {
		t.Fatalf("got %q", body)
	}

	Stop(client)
	Stop(client) // second stop is a no-op
	if c, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", socksPort), time.Second); err == nil {
		c.Close()
		t.Fatal("SOCKS5 inbound still accepts connections after Stop")
	}
}

// The call engine's path (jni/voip/fork): RFC 1928 UDP datagrams to the relay, no UDP ASSOCIATE, must come
// back from a local echo server with the echo server named in the header; Stop frees the relay port.
func TestUDPRelay(t *testing.T) {
	echo, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	go func() {
		buf := make([]byte, 2048)
		for {
			n, from, err := echo.ReadFrom(buf)
			if err != nil {
				return
			}
			echo.WriteTo(buf[:n], from)
		}
	}()
	socksPort, client := startVlessPair(t)
	if err := StartUDPRelay(client, socksPort); err != nil { // same number as the SOCKS5 port, like the app
		t.Fatal(err)
	}

	conn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	echoPort := echo.LocalAddr().(*net.UDPAddr).Port
	header := []byte{0, 0, 0, 1, 127, 0, 0, 1, byte(echoPort >> 8), byte(echoPort)}
	socks := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: socksPort}
	reply := make([]byte, 2048)
	for attempt := 0; attempt < 5; attempt++ { // the first datagram may race the session setup
		conn.WriteTo(append(append([]byte{}, header...), "ping"...), socks)
		conn.SetReadDeadline(time.Now().Add(2 * time.Second))
		n, _, err := conn.ReadFrom(reply)
		if err != nil {
			continue
		}
		if got, want := string(reply[:n]), string(header)+"ping"; got != want {
			t.Fatalf("got % x, want % x", reply[:n], []byte(want))
		}
		Stop(client)
		freed, err := net.ListenUDP("udp", socks)
		if err != nil {
			t.Fatal("relay port still bound after Stop:", err)
		}
		freed.Close()
		return
	}
	t.Fatal("no UDP reply through the relay")
}

func TestRejectsBadConfig(t *testing.T) {
	if _, err := Start(`{"outbounds":[{"protocol":"no-such-protocol"}]}`); err == nil {
		t.Fatal("expected an error")
	}
}
