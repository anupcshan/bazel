package cache

import (
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/anupcshan/bazel-build-worker/remote"
	"github.com/golang/protobuf/proto"
)

type Status int

const (
	MISSING  Status = iota // Not present in cache, default
	FETCHING Status = iota // Cache entry being fetched, not ready for use
	PRESENT  Status = iota // Present in cache

	// TODO(anupc): Cache cleanup
)

type DiskCache struct {
	backingCache   Cache
	cacheDir       string
	lock           sync.RWMutex
	state          map[string]Status
	ongoingFetches map[string]*sync.WaitGroup
}

func NewDiskCache(cacheDir string, backingCache Cache) *DiskCache {
	return &DiskCache{
		cacheDir:       cacheDir,
		backingCache:   backingCache,
		state:          make(map[string]Status),
		ongoingFetches: make(map[string]*sync.WaitGroup),
	}
}

func (dc *DiskCache) getState(key string) Status {
	dc.lock.RLock()
	defer dc.lock.RUnlock()

	status, ok := dc.state[key]
	if !ok {
		return MISSING
	}

	return status
}

var nilWg sync.WaitGroup

func (dc *DiskCache) claimFetchTask(key string) (bool, sync.WaitGroup) {
	dc.lock.Lock()
	defer dc.lock.Unlock()

	status, ok := dc.state[key]
	if ok && status != MISSING {
		return false, nilWg
	}

	dc.state[key] = FETCHING
	var wg sync.WaitGroup
	wg.Add(1)
	dc.ongoingFetches[key] = &wg
	return true, wg
}

func (dc *DiskCache) waitForTask(key string) {
	dc.lock.RLock()
	wg := dc.ongoingFetches[key]
	dc.lock.RUnlock()

	wg.Wait()
}

func (dc *DiskCache) releaseFetchTask(key string, status Status) {
	dc.lock.Lock()
	defer dc.lock.Unlock()

	dc.state[key] = status
	dc.ongoingFetches[key].Done()
}

func (dc *DiskCache) fetchKey(key string, executable bool) error {
	if claimed, wg := dc.claimFetchTask(key); !claimed {
		wg.Wait()
		// TODO(anupc): Check for fetch status
		return nil
	}

	b, err := dc.backingCache.Get(key)
	if err != nil {
		dc.releaseFetchTask(key, MISSING)
		return err
	}

	filePath := filepath.Join(dc.cacheDir, key)
	perm := os.FileMode(0644)
	if executable {
		perm = 0755
	}
	if f, err := os.OpenFile(filePath, os.O_CREATE|os.O_WRONLY, perm); err != nil {
		dc.releaseFetchTask(key, MISSING)
		return err
	} else {
		defer f.Close()
		cacheEntry := new(remote.CacheEntry)

		err = proto.Unmarshal(b, cacheEntry)
		if err != nil {
			dc.releaseFetchTask(key, MISSING)
			return err
		}
		_, err = f.Write(cacheEntry.FileContent)
		dc.releaseFetchTask(key, MISSING)
		return err
	}

	dc.releaseFetchTask(key, PRESENT)
	return nil
}

// Ensure a key is cached on disk for a given duration, returns the a "future" to the result
func (dc *DiskCache) EnsureCached(key string, executable bool, timeout time.Duration) <-chan error {
	errChan := make(chan error)

	go func(errCh chan<- error) {
		state := dc.getState(key)
		switch state {
		case PRESENT:
			// TODO(anupc): Should we stat the file to verify?
			errCh <- nil
			return
		case MISSING:
			errCh <- dc.fetchKey(key, executable)
			return
		case FETCHING:
			// TODO(anupc): Return reference to the future where fetch is happening
			dc.waitForTask(key)
			errCh <- nil
			return
		}
		// cachePath := filepath.Join(dc.cacheDir, key)
		errCh <- nil
	}(errChan)

	return errChan
}

func (dc *DiskCache) GetLink(key string) string {
	// TODO(anupc): Assert key in cache?
	return filepath.Join(dc.cacheDir, key)
}
