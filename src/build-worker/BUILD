load("@io_bazel_rules_go//go:def.bzl", "go_binary", "go_prefix")

go_binary(
    name = "build-worker",
    srcs = glob(["main.go"]),
    deps = [
        "//src/build-worker/cache:go_default_library",
        "//src/main/protobuf:go_default_library",
        "//src/build-worker/vendor/github.com/golang/protobuf/proto:go_default_library",
    ],
)
