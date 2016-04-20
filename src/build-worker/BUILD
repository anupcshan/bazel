load("@io_bazel_rules_go//go:def.bzl", "go_prefix", "go_binary")

go_prefix("github.com/anupcshan/bazel-build-worker")

go_binary(
    name = "build-worker",
    srcs = glob(["main.go"]),
    deps = [
        "//cache:go_default_library",
        "//remote:go_default_library",
        "//vendor/github.com/golang/protobuf/proto:go_default_library",
    ],
)
