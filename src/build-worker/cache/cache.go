package cache

type Cache interface {
	Get(string) ([]byte, error)
	Put(string, []byte) error
}
