package cache

import (
	"bytes"
	"fmt"
	"io"
	"io/ioutil"
	"net/http"
)

// HazelcastCache implements Cache interface backed by a Hazelcast/REST-based map
type HazelcastCache struct {
	hazelCastAPIBase string
	httpClient       *http.Client

	// TODO(anupc): Limit outstanding requests
}

func NewHazelcastCache(hazelCastAPIBase string) *HazelcastCache {
	return &HazelcastCache{hazelCastAPIBase: hazelCastAPIBase, httpClient: http.DefaultClient}
}

func (c *HazelcastCache) Get(key string) ([]byte, error) {
	resp, err := c.httpClient.Get(fmt.Sprintf("%s/%s", c.hazelCastAPIBase, key))
	if err != nil {
		// TODO(anupc): Retries
		return nil, err
	}

	if resp.StatusCode == http.StatusNoContent {
		return nil, io.ErrShortBuffer
	}

	defer resp.Body.Close()
	return ioutil.ReadAll(resp.Body)
}

func (c *HazelcastCache) Put(key string, b []byte) error {
	resp, err := c.httpClient.Post(fmt.Sprintf("%s/%s", c.hazelCastAPIBase, key), "application/binary", bytes.NewReader(b))
	if err != nil {
		// TODO(anupc): Retries
		return err
	}

	defer resp.Body.Close()

	return nil
}

var _ Cache = new(HazelcastCache)
