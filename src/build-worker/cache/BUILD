package(default_visibility = ["//visibility:public"])

load("@io_bazel_rules_go//go:def.bzl", "go_library", "go_prefix")

go_library(
    name = "go_default_library",
    srcs = glob(["*.go"]),
    deps = [
        "//src/main/protobuf:go_default_library",
        "//src/build-worker/vendor/github.com/golang/protobuf/proto:go_default_library",
    ],
)
