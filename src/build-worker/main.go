package main

import (
	"bytes"
	"crypto/md5"
	"encoding/hex"
	_ "expvar"
	"flag"
	"fmt"
	"io/ioutil"
	"log"
	"net/http"
	_ "net/http/pprof"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"runtime"
	"sync"
	"time"

	"src/build-worker/cache"
	remote "src/main/protobuf"

	"github.com/golang/protobuf/proto"
)

func respond(w http.ResponseWriter, workRes *remote.RemoteWorkResponse) {
	b, err := proto.Marshal(workRes)
	if err != nil {
		log.Println(err)
	} else {
		w.Write(b)
	}
}

func writeError(w http.ResponseWriter, statusCode int, workRes *remote.RemoteWorkResponse, err error) {
	w.WriteHeader(statusCode)
	workRes.Exception = err.Error()
	workRes.Success = false
	respond(w, workRes)
}

func linkCachedObject(relPath string, workDir string, cachePath string) error {
	filePath := filepath.Join(workDir, relPath)

	dir := path.Dir(filePath)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return err
	}

	return os.Symlink(cachePath, filePath)
}

func writeCacheEntry(cacheBaseURL string, key string, data []byte) error {
	cacheEntry := new(remote.CacheEntry)
	cacheEntry.FileContent = data
	writePath := fmt.Sprintf("%s/%s", cacheBaseURL, key)
	b, err := proto.Marshal(cacheEntry)
	if err != nil {
		return err
	}
	body := bytes.NewBuffer(b)
	if resp, err := http.Post(writePath, "application/binary", body); err != nil {
		return err
	} else {
		resp.Body.Close()
	}

	return nil
}

func writeActionCacheEntry(cacheBaseURL string, key string, cacheEntry *remote.CacheEntry) error {
	writePath := fmt.Sprintf("%s/%s", cacheBaseURL, key)
	b, err := proto.Marshal(cacheEntry)
	if err != nil {
		return nil
	}
	if resp, err := http.Post(writePath, "application/binary", bytes.NewBuffer(b)); err != nil {
		return err
	} else {
		resp.Body.Close()
	}

	return nil
}

type BuildRequestHandler struct {
	diskCache      *cache.DiskCache
	hazelcastCache cache.Cache
	workerPool     chan bool
}

func (bh *BuildRequestHandler) initializeWorkerPool(maxWorkers int) {
	bh.workerPool = make(chan bool, maxWorkers)
	for i := 0; i < maxWorkers; i++ {
		bh.workerPool <- true
	}
}

func (bh *BuildRequestHandler) takeWorker() {
	<-bh.workerPool
}

func (bh *BuildRequestHandler) releaseWorker() {
	bh.workerPool <- true
}

func (bh *BuildRequestHandler) HandleBuildRequest(w http.ResponseWriter, r *http.Request) {
	workReq := new(remote.RemoteWorkRequest)
	workRes := new(remote.RemoteWorkResponse)

	bh.takeWorker()
	defer bh.releaseWorker()

	b, err := ioutil.ReadAll(r.Body)
	if err != nil {
		writeError(w, http.StatusInternalServerError, workRes, err)
		return
	}

	err = proto.Unmarshal(b, workReq)
	if err != nil {
		writeError(w, http.StatusInternalServerError, workRes, err)
		return
	}

	logger := log.New(os.Stderr, fmt.Sprintf("[%s] ", workReq.OutputKey), log.Lshortfile|log.Lmicroseconds)

	workDir, err := ioutil.TempDir(*workdirRoot, "workdir")
	if err != nil {
		writeError(w, http.StatusInternalServerError, workRes, err)
		return
	}

	logger.Println("Creating workdir:", workDir)
	defer func(start time.Time) {
		os.RemoveAll(workDir)
		logger.Printf("Completed request in %s", time.Since(start))
	}(time.Now())

	var wg sync.WaitGroup
	for _, inputFile := range workReq.GetInputFiles() {
		wg.Add(1)
		go func(key string, executable bool) {
			<-bh.diskCache.EnsureCached(key, executable, 10*time.Minute)
			wg.Done()
		}(inputFile.ContentKey, inputFile.Executable)
	}

	cacheStart := time.Now()
	wg.Wait()
	logger.Printf("Completed caching input files in %s", time.Since(cacheStart))

	linkStart := time.Now()

	for _, inputFile := range workReq.GetInputFiles() {
		if err := linkCachedObject(inputFile.Path, workDir, bh.diskCache.GetLink(inputFile.ContentKey)); err != nil {
			writeError(w, http.StatusInternalServerError, workRes, err)
			return
		}
	}
	logger.Printf("Completed linking input files in %s", time.Since(linkStart))

	// Most actions expect directories for output files to exist up front.
	for _, outputFile := range workReq.GetOutputFiles() {
		filePath := filepath.Join(workDir, outputFile.Path)

		dir := path.Dir(filePath)
		if err := os.MkdirAll(dir, 0755); err != nil {
			writeError(w, http.StatusInternalServerError, workRes, err)
			return
		}
	}

	cmd := exec.Command(workReq.Arguments[0], workReq.Arguments[1:]...)
	var stdout bytes.Buffer
	var stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	cmd.Dir = workDir

	env := []string{}
	for key, value := range workReq.GetEnvironment() {
		env = append(env, fmt.Sprintf("%s=%s", key, value))
	}
	cmd.Env = env

	if *logCommands {
		logger.Println("Executing:", workReq.Arguments)
	}

	execStart := time.Now()
	err = cmd.Run()
	logger.Printf("Completed command execution in %s", time.Since(execStart))
	if err != nil {
		if *logCommands {
			logger.Println("===================")
			logger.Println("Execution failed:")
			logger.Println("STDOUT")
			logger.Println(stdout.String())
			logger.Println("STDERR")
			logger.Println(stderr.String())
			logger.Println("===================")
		}
		workRes.Out = stdout.String()
		workRes.Err = stderr.String()
		writeError(w, http.StatusOK, workRes, err)
		return
	}

	workRes.Out = stdout.String()
	workRes.Err = stderr.String()

	outputActionCache := new(remote.CacheEntry)

	writingOutputFilesStart := time.Now()
	for _, outputFile := range workReq.GetOutputFiles() {
		filePath := filepath.Join(workDir, outputFile.Path)
		if f, err := os.Open(filePath); err != nil {
			writeError(w, http.StatusOK, workRes, err)
			return
		} else if b, err := ioutil.ReadAll(f); err != nil {
			writeError(w, http.StatusOK, workRes, err)
			return
		} else {
			checksum := md5.Sum(b)
			logger.Printf("Writing %d bytes to cache", len(b))
			writeCacheEntry(*cacheBaseURL, hex.EncodeToString(checksum[:md5.Size]), b)
			outputFile.ContentKey = hex.EncodeToString(checksum[:md5.Size])
			outputActionCache.Files = append(outputActionCache.Files, outputFile)
		}
	}
	logger.Printf("Completed writing output files in %s", time.Since(writingOutputFilesStart))

	writingActionKeyStart := time.Now()
	writeActionCacheEntry(*cacheBaseURL, workReq.OutputKey, outputActionCache)
	logger.Printf("Completed writing action key in %s", time.Since(writingActionKeyStart))

	workRes.Success = true
	w.WriteHeader(http.StatusOK)
	respond(w, workRes)
}

func main() {
	flag.Parse()

	if *maxWorkers == 0 {
		*maxWorkers = runtime.NumCPU()
	}

	log.SetFlags(log.Lmicroseconds | log.Lshortfile)

	listenAddr := fmt.Sprintf(":%d", *port)

	hc := cache.NewHazelcastCache(*cacheBaseURL)
	if err := os.MkdirAll(*cacheDir, 0755); err != nil {
		log.Fatal(err)
	}

	diskCache := cache.NewDiskCache(*cacheDir, hc)

	buildRequestHandler := &BuildRequestHandler{hazelcastCache: hc, diskCache: diskCache}
	buildRequestHandler.initializeWorkerPool(*maxWorkers)

	// TODO(anupc): Add handler to drain pool and terminate cleanly.

	http.HandleFunc("/build", buildRequestHandler.HandleBuildRequest)

	err := http.ListenAndServe(listenAddr, nil)
	log.Fatal(err)
}

var (
	port         = flag.Int("port", 1234, "Port to listen on")
	cacheBaseURL = flag.String("cache-base-url", "http://localhost:5701/hazelcast/rest/maps/hazelcast-build-cache", "Base of cache URL to connect to")
	workdirRoot  = flag.String("workdir-root", "/tmp/", "Directory to create working subdirectories to execute actions in")
	cacheDir     = flag.String("cachedir", "/tmp/bazel-worker-cache", "Directory to store cached objects")
	logCommands  = flag.Bool("log-commands", true, "Log all command executions (include stdout/stderr in case of failures)")
	maxWorkers   = flag.Int("max-workers", 0, "Maximum number of parallel workers (defaults to number of CPUs)")
)