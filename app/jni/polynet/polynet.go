// Package polynet runs the bundled proxy core inside the app process.
// It is bound to Java with gomobile (see jni/prebuild/build_polynet.sh) and replaces exec'ing a core binary,
// which Android 12+ may kill on its own as a "phantom" child process.
package polynet

import (
	"sync"

	"github.com/xtls/xray-core/core"
	// ponytail: registers every protocol and the JSON loader (~all of the core's size);
	// build protobuf configs and import only vless/socks/transports if the library size matters.
	_ "github.com/xtls/xray-core/main/distro/all"
)

var (
	mu        sync.Mutex
	lastID    int64
	instances = map[int64]*core.Instance{}
)

// Start runs an instance from a JSON config and returns its handle; its inbounds are listening on return.
func Start(configJSON string) (int64, error) {
	instance, err := core.StartInstance("json", []byte(configJSON))
	if err != nil {
		return 0, err
	}
	mu.Lock()
	defer mu.Unlock()
	lastID++
	instances[lastID] = instance
	return lastID, nil
}

// Stop closes an instance returned by Start, and its UDP relay; unknown or already stopped handles are ignored.
func Stop(id int64) {
	mu.Lock()
	instance, relay := instances[id], relays[id]
	delete(instances, id)
	delete(relays, id)
	mu.Unlock()
	if relay != nil {
		relay.close()
	}
	if instance != nil {
		instance.Close()
	}
}
