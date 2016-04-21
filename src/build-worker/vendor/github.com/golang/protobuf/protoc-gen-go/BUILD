load("@io_bazel_rules_go//go:def.bzl", "go_binary", "go_prefix")

go_binary(
    name = "protoc-gen-go",
    srcs = ["link_grpc.go", "main.go"],
    deps = [
        "//src/build-worker/vendor/github.com/golang/protobuf/proto:go_default_library",
        "//src/build-worker/vendor/github.com/golang/protobuf/protoc-gen-go/generator:go_default_library",
        "//src/build-worker/vendor/github.com/golang/protobuf/protoc-gen-go/grpc:go_default_library",
    ],
)
